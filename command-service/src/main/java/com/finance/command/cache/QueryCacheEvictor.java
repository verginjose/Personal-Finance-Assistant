package com.finance.command.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Directly evicts query-service Redis keys from command-service.
 *
 * Both services share the same Redis instance but use different namespaces:
 *  - command-service  →  finance:upsert:v1:<cacheName>:<key>
 *  - query-service    →  finance:analytics:v1:<cacheName>:<key>
 *
 * Uses a double-delete pattern to handle read-replica replication lag:
 *   1. Delete immediately (removes pre-change cached value)
 *   2. Sleep REPLICATION_DELAY_MS (covers replica catch-up time)
 *   3. Delete again (removes any value that got refilled from a stale replica read)
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

    /** Time to wait between the two delete waves to cover replication lag */
    private static final long REPLICATION_DELAY_MS = 800L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheKeyRegistry cacheKeyRegistry;

    // ── User-level eviction (transactions, summary, search, …) ───────────────

    /**
     * Evict all Redis cache entries tracked for a user (query-service reads +
     * command-service upsert caches). Runs an immediate delete, then a second
     * delete after {@link #REPLICATION_DELAY_MS} to clear entries that were
     * repopulated from a lagging read replica.
     */
    public void evictUserKeysWithReplicationGuard(UUID userId) {
        cacheKeyRegistry.evictForUser(userId);
        evictUserKeysDelayed(userId);
    }

    @Async
    public void evictUserKeysDelayed(UUID userId) {
        try {
            Thread.sleep(REPLICATION_DELAY_MS);
            cacheKeyRegistry.evictForUser(userId);
            log.debug("QueryCacheEvictor: delayed second evict for user={}", userId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: delayed user eviction failed for user={}: {}", userId, e.getMessage());
        }
    }

    // ── Group-level eviction ──────────────────────────────────────────────────

    /**
     * Evict all query-service cache entries for the given group.
     * Covers: group-details, group-balances, group-expenses, group-activity,
     *         and user-groups for all member users.
     *
     * Uses double-delete to survive read-replica replication lag:
     *   Wave 1: delete everything now
     *   Sleep:  allow replica to catch up
     *   Wave 2: delete again (catches anything refilled from stale replica)
     */
    @Async
    public void evictGroupKeys(Long groupId, Set<UUID> memberIds) {
        try {
            // Wave 1 — delete current state
            doEvictGroup(groupId, memberIds);

            Thread.sleep(REPLICATION_DELAY_MS);

            // Wave 2 — delete anything refilled from stale replica during the gap
            doEvictGroup(groupId, memberIds);

            log.debug("QueryCacheEvictor: double-evicted groupId={}, {} members", groupId, memberIds.size());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: group eviction failed for groupId={}: {}", groupId, e.getMessage());
        }
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

            Thread.sleep(REPLICATION_DELAY_MS);

            doEvictUserGroups(userIds);

            log.debug("QueryCacheEvictor: double-evicted user-groups for {} users", userIds.size());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: user-groups eviction failed: {}", e.getMessage());
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
        Set<Object> groupKeys = redisTemplate.opsForSet().members(groupSet);
        if (groupKeys != null && !groupKeys.isEmpty()) {
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                for (Object k : groupKeys) {
                    connection.keyCommands().del(((String) k).getBytes());
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
                Set<Object> allKeys = redisTemplate.opsForSet().members(userSet);
                if (allKeys == null || allKeys.isEmpty()) {
                    // Fallback: delete the well-known hardcoded key in case the set wasn't populated yet
                    deleteKey(QUERY_PREFIX + "user-groups:" + uid);
                    continue;
                }

                // Filter for user-groups keys only
                Set<String> userGroupsKeys = allKeys.stream()
                        .map(Object::toString)
                        .filter(k -> k.contains(":user-groups:"))
                        .collect(Collectors.toSet());

                if (!userGroupsKeys.isEmpty()) {
                    redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                        for (String k : userGroupsKeys) {
                            connection.keyCommands().del(k.getBytes());
                        }
                        return null;
                    });
                    // Remove evicted keys from the registry set
                    redisTemplate.opsForSet().remove(userSet,
                            userGroupsKeys.toArray());
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

    private void deleteKey(String key) {
        Boolean deleted = redisTemplate.delete(key);
        log.trace("DEL {} → {}", key, deleted);
    }
}
