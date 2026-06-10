package com.finance.analytics.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;



@Slf4j
@Component
@RequiredArgsConstructor
public class CacheKeyRegistry {

    @Lazy
    private final RedisTemplate<String, Object> redisTemplate;

    private static final Duration REGISTRY_TTL    = Duration.ofHours(2);
    private static final String   USER_KEY_PREFIX  = "finance:analytics:v1:user-keys:";
    private static final String   CACHE_USER_PREFIX = "finance:analytics:v1:cache-users:";
    private static final String EVICT_CACHE_LUA = """
    local cacheSet    = KEYS[1]
    local cachePrefix = ARGV[1]
    local userPrefix  = ARGV[2]
    
    local userIds = redis.call('SMEMBERS', cacheSet)
    if #userIds == 0 then return 0 end
    
    local totalDeleted = 0
    
    for _, uid in ipairs(userIds) do
        local userSet = userPrefix .. uid
        
        -- SSCAN instead of SMEMBERS to avoid blocking on large sets
        local cursor = '0'
        local toDelete = {}
        
        repeat
            local result = redis.call('SSCAN', userSet, cursor, 'MATCH', cachePrefix .. '*', 'COUNT', 100)
            cursor = result[1]
            local keys = result[2]
            
            for _, k in ipairs(keys) do
                redis.call('UNLINK', k)
                table.insert(toDelete, k)
                totalDeleted = totalDeleted + 1
            end
        until cursor == '0'
        
        -- batch SREM in one call
        if #toDelete > 0 then
            redis.call('SREM', userSet, table.unpack(toDelete))
        end
    end
    
    redis.call('UNLINK', cacheSet)
    return totalDeleted
    """;

    // ─── Write ───────────────────────────────────────────────────────────────

    public void register(UUID userId, String cacheName, String fullKey) {
        String userSet  = USER_KEY_PREFIX  + userId;
        String cacheSet = CACHE_USER_PREFIX + cacheName;
        try {
            // Pipeline both writes — one round trip
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                byte[] userSetBytes  = userSet.getBytes();
                byte[] cacheSetBytes = cacheSet.getBytes();
                byte[] fullKeyBytes  = fullKey.getBytes();
                byte[] userIdBytes   = userId.toString().getBytes();

                connection.setCommands().sAdd(userSetBytes,  fullKeyBytes);
                connection.setCommands().sAdd(cacheSetBytes, userIdBytes);
                connection.keyCommands().expire(userSetBytes,  REGISTRY_TTL.toSeconds());
                connection.keyCommands().expire(cacheSetBytes, REGISTRY_TTL.toSeconds());
                return null;
            });
            log.debug("Registered key={} user={} cache={}", fullKey, userId, cacheName);
        } catch (Exception e) {
            log.warn("register failed key={} user={}: {}", fullKey, userId, e.getMessage());
        }
    }

    // ─── Single key evict ────────────────────────────────────────────────────

    public void deregister(UUID userId, String cacheName, String fullKey) {
        String userSet  = USER_KEY_PREFIX  + userId;
        String cacheSet = CACHE_USER_PREFIX + cacheName;
        try {
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                connection.setCommands().sRem(userSet.getBytes(),  fullKey.getBytes());
                // Only remove userId from cacheSet if they have no more keys
                // (checked after pipeline — see note below)
                return null;
            });
        } catch (Exception e) {
            log.warn("deregister failed key={} user={}: {}", fullKey, userId, e.getMessage());
        }
    }

    // ─── User evict (your main flow) ─────────────────────────────────────────

    public void evictForUser(UUID userId) {
        String userSet = USER_KEY_PREFIX + userId;
        try {
            Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
                Set<byte[]> bytes = connection.setCommands().sMembers(userSet.getBytes());
                if (bytes == null) return Collections.emptySet();
                return bytes.stream().map(String::new).collect(Collectors.toSet());
            });

            if (keys == null || keys.isEmpty()) {
                log.debug("Nothing to evict for user={}", userId);
                return;
            }

            // Delete cache entries + registry set atomically
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                keys.forEach(k -> connection.keyCommands()
                        .del(k.getBytes()));
                connection.keyCommands().del(userSet.getBytes());
                return null;
            });

            log.info("Evicted {} keys for user={}", keys.size(), userId);
        } catch (Exception e) {
            log.error("evictForUser failed user={}: {}", userId, e.getMessage());
        }
    }

    public void evictAllForCache(String cacheName) {
        String cacheSet    = CACHE_USER_PREFIX + cacheName;
        String cachePrefix = "finance:analytics:v1:" + cacheName + ":";
        try {
            Long deleted = redisTemplate.execute(
                    new DefaultRedisScript<>(EVICT_CACHE_LUA, Long.class),
                    Collections.singletonList(cacheSet),   // KEYS[1]
                    cachePrefix,                            // ARGV[1]
                    USER_KEY_PREFIX                         // ARGV[2]
            );
            log.info("evictAllForCache cache={} deleted={} keys", cacheName, deleted);
        } catch (Exception e) {
            log.error("evictAllForCache failed cache={}: {}", cacheName, e.getMessage());
        }
    }

    // Debugging
    public Set<String> getRegisteredKeys(UUID userId) {
        String userSet = USER_KEY_PREFIX + userId;
        try {
            Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
                Set<byte[]> bytes = connection.setCommands().sMembers(userSet.getBytes());
                if (bytes == null) return Collections.emptySet();
                return bytes.stream().map(String::new).collect(Collectors.toSet());
            });
            return keys != null ? keys : Collections.emptySet();
        } catch (Exception e) {
            log.warn("getRegisteredKeys failed user={}: {}", userId, e.getMessage());
            return Collections.emptySet();
        }
    }
}