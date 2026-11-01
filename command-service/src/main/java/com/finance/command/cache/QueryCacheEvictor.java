package com.finance.command.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evicts query-service Redis keys from command-service and publishes the
 * write-synchronisation tokens that let the reader decide whether a cache
 * fill is safe to store.
 *
 * Both services share the same Redis instance but use different namespaces:
 *  - command-service  →  finance:upsert:v1:<cacheName>:<key>
 *  - query-service    →  finance:analytics:v1:<cacheName>:<key>
 *
 * Invalidation is: delete now (wave 1) + bump {@code finance:gen:<scope>} and
 * publish the write's WAL position in {@code finance:wal:<scope>} after commit.
 * The old fixed 800 ms "wait for the replica" second delete is gone — the
 * reader checks the token instead of guessing a window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryCacheEvictor {

    /** Matches query-service RedisConfig computePrefixWith */
    private static final String QUERY_PREFIX      = "finance:analytics:v1:";

    /** Prefix for the group key tracking Set (written by query-service TrackingRedisCache) */
    private static final String GROUP_SET_PREFIX  = "finance:query:v1:group-keys:";

    /** Prefix for the per-user key tracking Set (written by query-service CacheKeyRegistry) */
    private static final String USER_SET_PREFIX   = "finance:analytics:v1:user-keys:";

    /**
     * Write-synchronisation tokens consumed by query-service (CacheConsistencyGuard).
     * Per scope we keep a generation counter (cheap "did a write land?" equality
     * check) and the WAL position of that write (so the reader can ask the
     * replica whether it has replayed far enough before caching a value).
     */
    private static final String GEN_PREFIX = "finance:gen:";
    private static final String WAL_PREFIX = "finance:wal:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final CacheKeyRegistry cacheKeyRegistry;

    // ── User-level eviction (transactions, summary, search, …) ───────────────

    /**
     * Evict the user's cached reads, then publish a write token after commit.
     * The token — not a timer — is what stops a stale refill from being
     * cached: query-service refuses to store a fill whose generation moved or
     * whose WAL position the replica has not replayed yet.
     */
    public void evictUserKeysWithReplicationGuard(UUID userId) {
        cacheKeyRegistry.evictForUser(userId);
        markWrite(Set.of(userId), Set.of());
    }

    /** Same contract as {@link #evictUserKeysWithReplicationGuard} for user-groups. */
    public void markUserWrites(Set<UUID> userIds) {
        markWrite(userIds, Set.of());
    }

    // ── Group-level eviction ──────────────────────────────────────────────────

    /**
     * Evict all query-service cache entries for the given group.
     * Covers: group-details, group-balances, group-expenses, group-activity,
     *         and user-groups for all member users. The deletion is immediate;
     *         the write token published after commit guards the refill race
     *         that the old delayed second delete used to paper over.
     */
    public void evictGroupKeys(Long groupId, Set<UUID> memberIds) {
        try {
            doEvictGroup(groupId, memberIds);
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: group eviction failed for groupId={}: {}", groupId, e.getMessage());
        }
        markWrite(memberIds, Set.of(groupId));
    }

    // ── User-level eviction ───────────────────────────────────────────────────

    /**
     * Evict the user-groups cache for the given users.
     * Reads the actual tracked keys from the user-key registry set rather
     * than constructing hardcoded key strings.
     */
    @Async
    public void evictUserGroupsKeys(Set<UUID> userIds) {
        try {
            doEvictUserGroups(userIds);
            log.debug("QueryCacheEvictor: evicted user-groups for {} users", userIds.size());
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: user-groups eviction failed: {}", e.getMessage());
        }
    }

    // ── Write tokens ──────────────────────────────────────────────────────────

    /**
     * Publish the write synchronisation tokens after the transaction commits.
     * Consumers (query-service CacheConsistencyGuard) use them to reject cache
     * fills that raced this write or that the read replica has not replayed.
     *
     * Runs in {@code afterCommit}, so a reader can never observe a token for a
     * write that is not yet visible on the primary.
     */
    private void markWrite(Set<UUID> userIds, Set<Long> groupIds) {
        Runnable publish = () -> publishWriteTokens(userIds, groupIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private void publishWriteTokens(Set<UUID> userIds, Set<Long> groupIds) {
        if ((userIds == null || userIds.isEmpty()) && (groupIds == null || groupIds.isEmpty())) {
            return;
        }
        // The commit record is flushed when afterCommit runs, so the current WAL
        // position is >= this transaction's commit. Requiring the replica to
        // replay past it therefore guarantees the write is visible there.
        String lsn = null;
        try {
            lsn = jdbcTemplate.queryForObject("select pg_current_wal_lsn()::text", String.class);
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: could not read WAL position: {}", e.getMessage());
        }

        final String wal = lsn;
        try {
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                if (userIds != null) {
                    for (UUID userId : userIds) {
                        bump(connection, "user:" + userId, wal);
                    }
                }
                if (groupIds != null) {
                    for (Long groupId : groupIds) {
                        bump(connection, "group:" + groupId, wal);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: failed to publish write tokens: {}", e.getMessage());
        }
    }

    private static void bump(RedisConnection connection, String scope, String wal) {
        connection.stringCommands().incr((GEN_PREFIX + scope).getBytes(StandardCharsets.UTF_8));
        if (wal != null) {
            connection.stringCommands().set(
                    (WAL_PREFIX + scope).getBytes(StandardCharsets.UTF_8),
                    wal.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Single eviction wave for a group:
     *  1. Evict everything stored in the group tracking Set (group-details, group-expenses,
     *     group-balances, group-activity) — written by query-service TrackingRedisCache.
     *  2. Evict user-groups keys for all members via their user-key registry sets.
     */
    private void doEvictGroup(Long groupId, Set<UUID> memberIds) {
        // 1. Group-tracked keys via Set registry
        String groupSet = GROUP_SET_PREFIX + groupId;
        Set<String> groupKeys = readSetMembers(groupSet);
        if (!groupKeys.isEmpty()) {
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                for (String k : groupKeys) {
                    connection.keyCommands().del(k.getBytes());
                }
                connection.keyCommands().del(groupSet.getBytes());
                return null;
            });
            log.trace("doEvictGroup: deleted {} group-tracked keys for groupId={}", groupKeys.size(), groupId);
        }

        // 2. user-groups keys for each member via their user-key registry
        doEvictUserGroups(memberIds);
    }

    /**
     * Evict all user-groups cache entries for the given users by reading
     * their user-key registry sets and filtering for user-groups keys.
     */
    private void doEvictUserGroups(Set<UUID> userIds) {
        for (UUID uid : userIds) {
            String userSet = USER_SET_PREFIX + uid;
            try {
                Set<String> allKeys = readSetMembers(userSet);
                if (allKeys.isEmpty()) {
                    // Fallback: delete the well-known hardcoded key in case the set wasn't populated yet
                    deleteKey(QUERY_PREFIX + "user-groups:" + uid);
                    continue;
                }

                // Filter for user-groups keys only
                Set<String> userGroupsKeys = allKeys.stream()
                        .filter(k -> k.contains(":user-groups:"))
                        .collect(Collectors.toSet());

                if (!userGroupsKeys.isEmpty()) {
                    redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                        byte[] userSetBytes = userSet.getBytes();
                        for (String k : userGroupsKeys) {
                            connection.keyCommands().del(k.getBytes());
                            connection.setCommands().sRem(userSetBytes, k.getBytes());
                        }
                        return null;
                    });
                    log.trace("doEvictUserGroups: deleted {} user-groups keys for user={}", userGroupsKeys.size(), uid);
                } else {
                    // No user-groups key tracked in set yet — use hardcoded fallback
                    deleteKey(QUERY_PREFIX + "user-groups:" + uid);
                }
            } catch (Exception e) {
                log.warn("doEvictUserGroups failed for user={}: {}", uid, e.getMessage());
                // Fallback to direct delete
                deleteKey(QUERY_PREFIX + "user-groups:" + uid);
            }
        }
    }

    /**
     * Registry sets are written with raw byte commands by both services.
     * Read them raw so {@link RedisTemplate}'s JSON value serializer never
     * tries to deserialize plain key strings. Also tolerates the legacy
     * JSON-encoded group entries during their TTL window.
     */
    private Set<String> readSetMembers(String setKey) {
        Set<byte[]> raw = redisTemplate.execute((RedisCallback<Set<byte[]>>) connection ->
                connection.setCommands().sMembers(setKey.getBytes()));
        if (raw == null) return Set.of();
        return raw.stream().map(QueryCacheEvictor::decodeMember).collect(Collectors.toSet());
    }

    private static String decodeMember(byte[] raw) {
        String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void deleteKey(String key) {
        Boolean deleted = redisTemplate.delete(key);
        log.trace("DEL {} → {}", key, deleted);
    }
}
