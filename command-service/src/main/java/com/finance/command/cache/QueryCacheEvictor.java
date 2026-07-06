package com.finance.command.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Directly evicts query-service Redis keys from command-service.
 *
 * Both services share the same Redis instance but use different namespaces:
 *  - command-service  →  finance:upsert:v1:<cacheName>:<key>
 *  - query-service    →  finance:analytics:v1:<cacheName>:<key>
 *
 * Since they're on the same Redis, command-service can directly DELETE
 * query-service keys by scanning the known prefix — no pub/sub required.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryCacheEvictor {

    private static final String QUERY_PREFIX = "finance:analytics:v1:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Evict all query-service cache entries for the given group
     * (group-details for all members, group-balances, group-expenses, user-groups entries).
     */
    @Async
    public void evictGroupKeys(Long groupId, Set<UUID> memberIds) {
        try {
            // 1. group-details — keyed as  groupId-userId  for each member
            String detailsPrefix = QUERY_PREFIX + "group-details:" + groupId + "-";
            for (UUID uid : memberIds) {
                deleteKey(detailsPrefix + uid);
            }

            // 2. group-balances — keyed as  groupId
            deleteKey(QUERY_PREFIX + "group-balances:" + groupId);

            // 3. group-expenses — keyed as  groupId-page-size  → scan pattern
            scanAndDelete(QUERY_PREFIX + "group-expenses:" + groupId + "-*");

            // 4. user-groups  — keyed as userId for each member
            for (UUID uid : memberIds) {
                deleteKey(QUERY_PREFIX + "user-groups:" + uid);
            }

            log.debug("QueryCacheEvictor: evicted keys for groupId={}, {} members", groupId, memberIds.size());
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: eviction failed for groupId={}: {}", groupId, e.getMessage());
        }
    }

    /** Evict only user-groups cache for a set of users (lightweight, e.g. after member changes). */
    @Async
    public void evictUserGroupsKeys(Set<UUID> userIds) {
        try {
            for (UUID uid : userIds) {
                deleteKey(QUERY_PREFIX + "user-groups:" + uid);
            }
            log.debug("QueryCacheEvictor: evicted user-groups for {} users", userIds.size());
        } catch (Exception e) {
            log.warn("QueryCacheEvictor: eviction of user-groups failed: {}", e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void deleteKey(String key) {
        Boolean deleted = redisTemplate.delete(key);
        log.trace("DEL {} → {}", key, deleted);
    }

    private void scanAndDelete(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            Long count = redisTemplate.delete(keys);
            log.debug("SCAN+DEL pattern={} deleted={}", pattern, count);
        }
    }
}
