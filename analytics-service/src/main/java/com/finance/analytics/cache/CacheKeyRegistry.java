package com.finance.analytics.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.cache.CacheMetricsRegistrar;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
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

    // Slightly longer than the longest cache TTL (timeline-analytics = 1h)
    // so the registry never expires before the keys it tracks
    private static final Duration REGISTRY_TTL = Duration.ofHours(2);
    private static final String REGISTRY_PREFIX = "finance:analytics:v1:user-keys:";

    /**
     * Register a cache key under the user's registry set.
     * Called whenever a cache entry is written.
     */
    public void register(UUID userId, String fullKey) {
        String setKey = REGISTRY_PREFIX + userId;
        try {
            redisTemplate.opsForSet().add(setKey, fullKey);
            // Refresh TTL on every write — keeps the registry alive as long as
            // the user is active
            redisTemplate.expire(setKey, REGISTRY_TTL);
            log.debug("Registered cache key={} for user={}", fullKey, userId);
        } catch (Exception e) {
            // Registry failure must never affect the caller —
            // worst case is a stale key that survives until TTL
            log.warn("Failed to register cache key={} for user={}: {}", fullKey, userId, e.getMessage());
        }
    }

    /**
     * Delete all cache keys registered for this user, then clean up the registry set.
     * O(1) SET lookup + bulk DELETE — no Redis SCAN needed.
     */
    public void evictForUser(UUID userId) {
        String setKey = REGISTRY_PREFIX + userId;
        try {
            Set<Object> members = redisTemplate.opsForSet().members(setKey);
            if (members == null || members.isEmpty()) {
                log.debug("No registered keys to evict for user={}", userId);
                return;
            }

            List<String> keys = members.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());

            // Delete the actual cache entries
            redisTemplate.delete(keys);
            // Delete the registry set itself
            redisTemplate.delete(setKey);

            log.debug("Evicted {} keys for user={}", keys.size(), userId);

        } catch (Exception e) {
            log.error("Eviction failed for user={}: {}", userId, e.getMessage());
        }
    }

    /** Expose registry contents for debugging/monitoring */
    public Set<String> getRegisteredKeys(UUID userId) {
        String setKey = REGISTRY_PREFIX + userId;
        try {
            Set<Object> members = redisTemplate.opsForSet().members(setKey);
            if (members == null) return Collections.emptySet();
            return members.stream().map(Object::toString).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Could not fetch registry for user={}: {}", userId, e.getMessage());
            return Collections.emptySet();
        }
    }
}

