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
 * invalidation race) and (b) the read replica has replayed past the write's WAL
 * position (the value the loader just read is not older than the last write).
 * Otherwise the value is returned to the caller but not cached, so a stale
 * value can never stick for the cache's TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheConsistencyGuard {

    private static final String GEN_PREFIX = "finance:gen:";
    private static final String WAL_PREFIX = "finance:wal:";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public record Snapshot(String gen, String wal) {}

    /**
     * Reads the tokens before the value is loaded.
     *
     * @return the tokens, or null if they could not be read (the caller then
     *         declines to cache — an uncached read is always safe).
     */
    public Snapshot snapshot(String scope) {
        try {
            List<String> values = redis.opsForValue()
                    .multiGet(List.of(GEN_PREFIX + scope, WAL_PREFIX + scope));
            if (values == null || values.size() < 2) {
                return null;
            }
            return new Snapshot(values.get(0), values.get(1));
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
            Snapshot after = snapshot(scope);
            if (after == null) {
                return false;
            }
            if (!Objects.equals(before.gen(), after.gen())) {
                // A write committed while we were loading: our value may be
                // pre-write, so don't let it stick in the cache.
                log.debug("CacheConsistencyGuard: gen moved for scope={}, skipping cache write", scope);
                return false;
            }
            return replicaHasReplayed(after.wal());
        } catch (Exception e) {
            log.warn("CacheConsistencyGuard: guard failed for scope={}: {}", scope, e.getMessage());
            return false; // fail closed — an uncached read is always safe
        }
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
