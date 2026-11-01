package com.finance.query.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps Spring's RedisCache to intercept cache writes.
 * Every time a cache entry is written, the key is registered in CacheKeyRegistry
 * so it can be bulk-deleted at eviction time without SCAN.
 *
 * All other operations (get, evict, clear) delegate straight to the underlying RedisCache.
 */
@Slf4j
public class TrackingRedisCache extends RedisCache {

    /** Matches the UUID embedded in user-scoped cache keys. Compiled once — this runs on every cache access. */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

    /** Caches whose keys are {@code <groupId>[-<suffix>]} and need group-level tracking. */
    private static final Set<String> GROUP_SCOPED_CACHES = Set.of(
            "group-details", "group-expenses", "group-balances", "group-activity");

    private final CacheKeyRegistry registry;
    private final String keyPrefix;

    public TrackingRedisCache(String name,
                              RedisCacheWriter cacheWriter,
                              RedisCacheConfiguration config,
                              CacheKeyRegistry registry) {
        super(name, cacheWriter, config);
        this.registry = registry;
        this.keyPrefix = config.getKeyPrefixFor(name);
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

    /**
     * @Cacheable(sync = true) does NOT go through putIfAbsent(). Spring hands the
     * whole load to RedisCacheWriter#get(name, key, valueLoader, ...), which writes
     * the entry itself, so the overrides above never see sync caches
     * (transactions, user-groups, group-*). Their keys therefore never landed in
     * CacheKeyRegistry and eviction could not find them — the entry stayed stale
     * for its full TTL. Register here too; SADD is idempotent, so hits are cheap.
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        T value = super.get(key, valueLoader);
        if (value != null) {            // null was not cached — nothing to track
            register(key);
        }
        return value;
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
        resolve(key, true);
    }

    private void deregister(Object key) {
        resolve(key, false);
    }

    /**
     * Resolve a cache key into its tracking registries. Runs on every cache
     * put/hit, so keep it allocation-light: precompiled pattern, no split().
     */
    private void resolve(Object key, boolean register) {
        try {
            String keyStr  = key.toString();
            String fullKey = keyPrefix + keyStr;

            if (GROUP_SCOPED_CACHES.contains(getName())) {
                int dash = keyStr.indexOf('-');
                String firstPart = dash < 0 ? keyStr : keyStr.substring(0, dash);
                try {
                    Long groupId = Long.parseLong(firstPart);
                    if (register) {
                        registry.registerGroup(groupId, fullKey);
                    } else {
                        registry.deregisterGroup(groupId, fullKey);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse groupId from key={}", keyStr);
                }
            }

            Matcher matcher = UUID_PATTERN.matcher(keyStr);
            if (matcher.find()) {
                UUID userId = UUID.fromString(matcher.group());
                if (register) {
                    registry.register(userId, getName(), fullKey);
                } else {
                    registry.deregister(userId, getName(), fullKey);
                }
            }
        } catch (Exception e) {
            log.warn("Key resolution failed key={}: {}", key, e.getMessage());
        }
    }
}