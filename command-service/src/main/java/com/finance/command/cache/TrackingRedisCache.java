package com.finance.command.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.util.UUID;
import java.util.function.BiConsumer;

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

    public TrackingRedisCache(String name,
                              RedisCacheWriter cacheWriter,
                              RedisCacheConfiguration config,
                              CacheKeyRegistry registry) {
        super(name, cacheWriter, config);
        this.registry = registry;
    }

    // ── WRITES ────────────────────────────────────────────────────────────────

    /**
     * @CachePut always calls put().
     * Register AFTER super succeeds — don't register if Redis write threw.
     */
    @Override
    public void put(Object key, @Nullable Object value) {
        super.put(key, value);          // throws on failure → register never called
        register(key);
    }

    /**
     * @Cacheable calls putIfAbsent(), NOT put().
     *
     * From Spring source:
     *   returns null        → key was absent, entry just written → register
     *   returns ValueWrapper → key already existed, nothing written → skip
     */
    @Override
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = super.putIfAbsent(key, value);
        if (existing == null) {         // null = just written
            register(key);
        }
        return existing;                // must return as-is — Spring uses this
    }

    // ── EVICTS ────────────────────────────────────────────────────────────────

    /**
     * @CacheEvict calls evict() — deferred/async removal.
     * Deregister AFTER super so registry stays consistent.
     */
    @Override
    public void evict(Object key) {
        super.evict(key);
        deregister(key);
    }

    /**
     * @CacheEvict can also call evictIfPresent() — guaranteed immediate removal.
     * From Spring source: default just calls evict() and returns false.
     * Override here too so a future Spring upgrade doesn't bypass your evict().
     */
    @Override
    public boolean evictIfPresent(Object key) {
        boolean existed = super.evictIfPresent(key);
        deregister(key);                // deregister regardless — safe if key wasn't there
        return existed;
    }

    /**
     * @CacheEvict(allEntries = true) calls clear().
     * You can't resolve individual users here without a reverse index.
     * Registry sets will drain naturally via TTL.
     */
    @Override
    public void clear() {
        super.clear();
        registry.evictAllForCache(getName());   // logs warning — see CacheKeyRegistry
    }

    /**
     * Same as clear() but guaranteed immediate.
     * Spring source default just calls clear() — override for same reason as evictIfPresent.
     */
    @Override
    public boolean invalidate() {
        boolean hadEntries = super.invalidate();
        registry.evictAllForCache(getName());
        return hadEntries;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void register(Object key) {
        resolve(key, (userId, fullKey) ->
                registry.register(userId, getName(), fullKey));
    }

    private void deregister(Object key) {
        resolve(key, (userId, fullKey) ->
                registry.deregister(userId, getName(), fullKey));
    }

    /**
     * Single key parser — userId is always parts[0], separated by |
     * Works for every @Cacheable pattern:
     *   "ai-insights"              → key = {userId}
     *   "comprehensive-analytics"  → key = {userId}|MONTHLY|2024-01|2024-12
     *   "category-analytics"       → key = {userId}|FOOD|2024-01|2024-12
     */
    private void resolve(Object key, BiConsumer<UUID, String> action) {
        try {
            String keyStr  = key.toString();
            String fullKey = getCacheConfiguration()
                    .getKeyPrefixFor(getName()) + keyStr;

            String userIdStr = keyStr.split(":")[0];

            if (userIdStr.length() != 36) {
                log.warn("Skipping non-user key: {}", keyStr);
                return;
            }
            action.accept(UUID.fromString(userIdStr), fullKey);
        } catch (Exception e) {
            log.warn("Key resolution failed key={}: {}", key, e.getMessage());
        }
    }
}