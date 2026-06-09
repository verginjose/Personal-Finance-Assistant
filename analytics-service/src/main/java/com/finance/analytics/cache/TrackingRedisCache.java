package com.finance.analytics.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Wraps Spring's RedisCache to intercept put() calls.
 * Every time a cache entry is written, the key is registered in CacheKeyRegistry
 * so it can be bulk-deleted at eviction time without SCAN.
 *
 * All other operations (get, evict, clear) delegate straight to the underlying RedisCache.
 */

@Slf4j
public class TrackingRedisCache extends RedisCache {

    private final CacheKeyRegistry registry;

    public TrackingRedisCache(RedisCache delegate, CacheKeyRegistry registry) {
        super(delegate.getName(),
              delegate.getNativeCache(),    // RedisCacheWriter
              delegate.getCacheConfiguration());  // RedisCacheConfiguration
        this.registry = registry;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        super.put(key, value);
        extractUserIdAndRegister(key);
    }

    // putIfAbsent — delete entirely, default will call your put()

    private void extractUserIdAndRegister(Object key) {
        try {
            String keyStr = key.toString();
            String fullRedisKey = "finance:analytics:v1:" + getName() + "::" + keyStr;
            String[] parts = keyStr.split(":");
            UUID userId = UUID.fromString(parts[0]);
            registry.register(userId, fullRedisKey);
        } catch (Exception e) {
            log.warn("Could not register cache key={}: {}", key, e.getMessage());
        }
    }
}
