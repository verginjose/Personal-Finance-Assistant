package com.finance.query.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;
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

    /**
     * Snapshot taken by the cache lookup that missed, consumed by the
     * putIfAbsent that follows the load. Thread-local because the two calls
     * happen on the same request thread with no shared handle.
     */
    private static final ThreadLocal<Map<String, CacheConsistencyGuard.Snapshot>> PENDING_SNAPSHOTS =
            ThreadLocal.withInitial(HashMap::new);

    private final CacheKeyRegistry registry;
    private final CacheConsistencyGuard consistencyGuard;
    private final String keyPrefix;

    public TrackingRedisCache(String name,
                              RedisCacheWriter cacheWriter,
                              RedisCacheConfiguration config,
                              CacheKeyRegistry registry,
                              CacheConsistencyGuard consistencyGuard) {
        super(name, cacheWriter, config);
        this.registry = registry;
        this.consistencyGuard = consistencyGuard;
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
     * Records the write tokens when a lookup misses so the load that follows
     * can tell whether a write raced it. Only used by non-sync caches; the
     * sync path takes its snapshot inside {@link #get(Object, Callable)}.
     */
    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper value = super.get(key);
        String scope = scopeOf(key);
        if (scope != null) {
            Map<String, CacheConsistencyGuard.Snapshot> pending = PENDING_SNAPSHOTS.get();
            String pendingKey = getName() + "::" + key;
            if (value == null) {
                if (pending.size() > 64) {
                    pending.clear();     // bound the leak from loads that never put
                }
                pending.put(pendingKey, consistencyGuard.snapshot(scope));
            } else {
                pending.remove(pendingKey);
            }
        }
        return value;
    }

    /**
     * @Cacheable(sync = true) does NOT go through putIfAbsent(). Spring hands the
     * whole load to the cache writer. Caching is gated by
     * {@link CacheConsistencyGuard}: a value loaded while a write raced it, or
     * from a replica that has not replayed the write, is returned but not
     * stored. Register here too so eviction can find sync-cache keys.
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        String scope = scopeOf(key);
        if (scope == null) {
            T value = super.get(key, valueLoader);
            if (value != null) {        // null was not cached — nothing to track
                register(key);
            }
            return value;
        }

        ValueWrapper cached = super.get(key);
        if (cached != null) {
            register(key);
            @SuppressWarnings("unchecked")
            T hit = (T) cached.get();
            return hit;
        }

        // Only a write that raced this load — or a replica that has not caught
        // up — can make the loaded value stale. The tokens are read before the
        // load and checked after it; when either moved, the value is returned
        // but not stored, so it cannot stick in the cache for its TTL.
        CacheConsistencyGuard.Snapshot before = consistencyGuard.snapshot(scope);
        T value = loadValue(key, valueLoader);
        if (value != null && consistencyGuard.canCache(scope, before)) {
            super.put(key, value);
            register(key);
        }
        return value;
    }

    /**
     * @Cacheable without {@code sync = true} loads first and then calls
     * {@code putIfAbsent}. The snapshot taken by the preceding cache lookup is
     * remembered per thread so the same before/after check can guard the put.
     */
    @Override
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        String scope = scopeOf(key);
        if (scope != null && value != null) {
            String pendingKey = getName() + "::" + key;
            CacheConsistencyGuard.Snapshot before = PENDING_SNAPSHOTS.get().remove(pendingKey);
            if (before == null) {
                before = consistencyGuard.snapshot(scope);
            }
            if (!consistencyGuard.canCache(scope, before)) {
                return null;            // not stored; the caller already has the value
            }
        }
        ValueWrapper existing = super.putIfAbsent(key, value);
        if (existing == null) {         // null = just written
            register(key);
        }
        return existing;
    }

    /** Runs the loader, wrapping checked exceptions the way Spring's cache does. */
    private static <T> T loadValue(Object key, Callable<T> valueLoader) {
        try {
            return valueLoader.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    /**
     * Scope of a cache key: {@code group:<id>} for group-scoped caches,
     * {@code user:<uuid>} when the key embeds a UUID, else null (no guard).
     * Must match the scope strings command-service publishes the tokens under.
     */
    private String scopeOf(Object key) {
        String keyStr = key.toString();
        if (GROUP_SCOPED_CACHES.contains(getName())) {
            int dash = keyStr.indexOf('-');
            return "group:" + (dash < 0 ? keyStr : keyStr.substring(0, dash));
        }
        Matcher matcher = UUID_PATTERN.matcher(keyStr);
        return matcher.find() ? "user:" + matcher.group() : null;
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