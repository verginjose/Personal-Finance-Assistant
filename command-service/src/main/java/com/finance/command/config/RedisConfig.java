package com.finance.command.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finance.command.cache.CacheKeyRegistry;
import com.finance.command.cache.TrackingRedisCache;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.cache.CacheMetricsRegistrar;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.NonNull;

import java.util.Map;
import java.util.HashMap;

import java.time.Duration;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Pool config
        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> poolConfig =
                new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(10);       // matches max-active
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWait(Duration.ofMillis(200));


        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        LettucePoolingClientConfiguration clientConfig =
                LettucePoolingClientConfiguration.builder()
                        .poolConfig(poolConfig)
                        .commandTimeout(Duration.ofMillis(2000))
                        .build();

        RedisStandaloneConfiguration serverConfig =
                new RedisStandaloneConfiguration(redisHost, redisPort);

        if (redisPassword != null && !redisPassword.isBlank()) {
            serverConfig.setPassword(RedisPassword.of(redisPassword));
        }

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisCacheWriter redisCacheWriter(RedisConnectionFactory connectionFactory) {
        return RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);
    }

    @Bean
    public RedisCacheManager cacheManager(
            RedisCacheWriter redisCacheWriter,           // ✅ injected — reused everywhere
            @Lazy CacheKeyRegistry cacheKeyRegistry) {  // ✅ removed connectionFactory — not needed here

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .computePrefixWith(cacheName ->
                        "finance:upsert:v1:" + cacheName + ":")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jsonSerializer));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("transactions",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("group-activity",
                defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigs.put("group-balances",
                defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("group-expenses",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("user-groups",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("group-details",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        return new RedisCacheManager(
                redisCacheWriter,    // ✅ same instance
                defaultConfig,
                cacheConfigs         // ✅ per-cache TTLs now actually applied
        ) {
            @Override
            @NonNull
            protected RedisCache createRedisCache(
                    @NonNull String name,
                    @Nullable RedisCacheConfiguration cfg) {

                RedisCacheConfiguration resolved = cfg != null ? cfg : defaultConfig;

                return new TrackingRedisCache(
                        name,
                        redisCacheWriter,   // ✅ same instance
                        resolved,
                        cacheKeyRegistry
                );
            }
        };
    }
    // Circuit breaker — Redis down won't crash your app
    @Bean
    public CachingConfigurer cachingConfigurer() {
        return new CachingConfigurer() {
            @Override
            public CacheErrorHandler errorHandler() {
                return new CacheErrorHandler() {
                    @Override
                    public void handleCacheGetError(@NonNull RuntimeException e,@NonNull Cache cache,@NonNull Object key) {
                        log.warn("Redis GET failed, hitting DB: {}", e.getMessage());
                    }
                    @Override
                    public void handleCachePutError(@NonNull RuntimeException e,@NonNull Cache cache,@NonNull Object key, Object value) {
                        log.warn("Redis PUT failed: {}", e.getMessage());
                    }
                    @Override
                    public void handleCacheEvictError(@NonNull RuntimeException e,@NonNull Cache cache,@NonNull Object key) {
                        log.error("Redis EVICT failed — stale data risk: {}", e.getMessage());
                    }
                    @Override
                    public void handleCacheClearError(@NonNull RuntimeException e,@NonNull Cache cache) {
                        log.error("Redis CLEAR failed: {}", e.getMessage());
                    }
                };
            }
        };
    }

    @Bean
    public SmartInitializingSingleton cacheMetricsBinder(
            RedisCacheManager cacheManager,
            CacheMetricsRegistrar cacheMetricsRegistrar) {
        return () -> {
            cacheManager.getCacheNames()
                    .forEach(name -> cacheMetricsRegistrar
                            .bindCacheToRegistry(cacheManager.getCache(name)));
        };
    }

    // Add this bean to your existing RedisConfig.java

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Same serializers as your cache config for consistency
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper()));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper())); // add
        template.afterPropertiesSet();
        return template;
    }

    public ObjectMapper redisObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType("com.finance.command")
                                .allowIfSubType("org.springframework.data.domain.")
                                .allowIfSubType("java.util.")
                                .allowIfSubType("java.time.")
                                .build(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                );
    }
}