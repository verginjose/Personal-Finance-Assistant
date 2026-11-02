package com.finance.query.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Decides whether a cache fill may be stored, without any timing guess.
 *
 * <p>command-service publishes two keys per scope after each committed write:
 * <ul>
 *   <li>{@code finance:gen:<scope>} — generation counter, INCR per write.</li>
 *   <li>{@code finance:wal:<scope>} — the write's WAL position.</li>
 * </ul>
 * scope is {@code user:<uuid>} or {@code group:<id>}.
 *
 * <p>A cache fill is stored only if (a) the generation did not change while the
 * value was being loaded (no write raced the fill — the classic cache-aside
 * invalidation race) and (b) the read replica had <em>already</em> replayed
 * past the write's WAL position when the load started — so the row the loader
 * reads cannot predate the last write. Condition (b) is captured in the
 * pre-load snapshot: checking the replica only after the load let a replica
 * that replayed a delete during the load certify a pre-delete row as fresh,
 * which then stuck for the cache's TTL. Otherwise the value is returned to the
 * caller but not cached, so a stale value can never stick for the cache's TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheConsistencyGuard {

    private static final String GEN_PREFIX = "finance:gen:";
    private static final String WAL_PREFIX = "finance:wal:";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    /**
     * @param replicaCaughtUp whether the read replica had already replayed
     *                        past {@code wal} when this snapshot was taken
     */
    public record Snapshot(String gen, String wal, boolean replicaCaughtUp) {}

    /**
     * Reads the tokens and the replica's position before the value is loaded.
     * The replica check must happen here, not after the load: a replica that
     * replays a delete while the loader runs would otherwise make a
     * pre-delete row look safe to cache.
     *
     * @return the snapshot, or null if it could not be taken (the caller then
     *         declines to cache — an uncached read is always safe).
     */
    public Snapshot snapshot(String scope) {
        try {
            List<String> values = readTokens(scope);
            if (values == null || values.size() < 2) {
                return null;
            }
            String gen = values.get(0);
            String wal = values.get(1);
            return new Snapshot(gen, wal, replicaHasReplayed(wal));
        } catch (Exception e) {
            log.warn("CacheConsistencyGuard: token read failed for scope={}: {}", scope, e.getMessage());
            return null;
        }
    }

    /**
     * @return true if the loaded value may be cached.
     */
    public boolean canCache(String scope, Snapshot before) {
        if (before == null) {
            return false;
        }
        try {
            List<String> after = readTokens(scope);
            if (after == null || after.size() < 2) {
                return false;
            }
            if (!Objects.equals(before.gen(), after.get(0))) {
                // A write committed while we were loading: our value may be
                // pre-write, so don't let it stick in the cache.
                log.debug("CacheConsistencyGuard: gen moved for scope={}, skipping cache write", scope);
                return false;
            }
            if (!before.replicaCaughtUp()) {
                // The replica had not applied the last write when this load
                // started, so the value it read may predate that write. It may
                // have caught up since; that must not retroactively bless the
                // value, or a deleted row would be cached for the whole TTL.
                log.debug("CacheConsistencyGuard: replica behind at load start for scope={}, skipping cache write", scope);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("CacheConsistencyGuard: guard failed for scope={}: {}", scope, e.getMessage());
            return false; // fail closed — an uncached read is always safe
        }
    }

    private List<String> readTokens(String scope) {
        return redis.opsForValue()
                .multiGet(List.of(GEN_PREFIX + scope, WAL_PREFIX + scope));
    }

    /**
     * Asks the read-side database whether it has replayed past {@code lsn}.
     * On a primary (or when the token is unknown) there is nothing to wait for.
     */
    private boolean replicaHasReplayed(String lsn) {
        if (lsn == null || lsn.isBlank()) {
            return true;
        }
        Boolean caughtUp = jdbc.queryForObject(
                "select coalesce(pg_last_wal_replay_lsn() >= ?::pg_lsn, true)",
                Boolean.class, lsn);
        return Boolean.TRUE.equals(caughtUp);
    }
}
