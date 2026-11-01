package com.finance.query.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.finance.query.cache.CacheKeyRegistry;
import com.finance.query.cache.TrackingRedisCache;
import io.lettuce.core.api.StatefulConnection;
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
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig =
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
            @Lazy CacheKeyRegistry cacheKeyRegistry,
            com.finance.query.cache.CacheConsistencyGuard consistencyGuard) {

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .computePrefixWith(cacheName ->
                        "finance:analytics:v1:" + cacheName + ":")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jsonSerializer));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("category-analytics",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("timeline-analytics",
                defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigs.put("comprehensive-analytics",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

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
                        cacheKeyRegistry,
                        consistencyGuard
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
        // Spring Data's PageImpl has no default constructor or @JsonCreator, so the
        // generic serializer can write Page<> values but cannot read them back —
        // every cached list cache (transactions, group-expenses) then throws
        // "Cannot construct instance of PageImpl" on GET and falls back to the DB.
        // Register a deserializer that rebuilds the page from its JSON fields.
        SimpleModule pageModule = new SimpleModule("redis-page-module");
        pageModule.addDeserializer(PageImpl.class, new PageImplDeserializer());

        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(pageModule)
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType("com.finance.query")
                                .allowIfSubType("org.springframework.data.domain.")
                                .allowIfSubType("java.util.")
                                .allowIfSubType("java.time.")
                                .allowIfSubType("java.math.")
                                .build(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                );
    }

    /**
     * Rebuilds a {@link PageImpl} from the JSON produced by Jackson. Only the
     * fields needed to reconstruct an equivalent page are read: content,
     * totalElements and the page number/size from the nested pageable.
     */
    static class PageImplDeserializer extends JsonDeserializer<PageImpl<?>> {
        @Override
        public PageImpl<?> deserialize(JsonParser p, DeserializationContext ctxt) throws java.io.IOException {
            JsonNode node = p.getCodec().readTree(p);

            java.util.List<Object> content = new java.util.ArrayList<>();
            JsonNode contentNode = node.get("content");
            if (contentNode != null && contentNode.isArray()) {
                // Default typing wraps collections as ["<impl-class>", [items]].
                if (contentNode.size() == 2 && contentNode.get(0).isTextual()
                        && contentNode.get(1).isArray()) {
                    contentNode = contentNode.get(1);
                }
                for (JsonNode item : contentNode) {
                    content.add(p.getCodec().treeToValue(item, Object.class));
                }
            }

            int pageNumber = 0;
            int pageSize = Math.max(content.size(), 1);
            JsonNode pageable = node.get("pageable");
            if (pageable != null && pageable.isObject()) {
                if (pageable.has("pageNumber")) pageNumber = pageable.get("pageNumber").asInt(0);
                if (pageable.has("pageSize")) pageSize = pageable.get("pageSize").asInt(pageSize);
            } else if (node.has("number")) {
                pageNumber = node.get("number").asInt(0);
            }
            if (pageSize <= 0) pageSize = Math.max(content.size(), 1);

            long total = node.has("totalElements") ? node.get("totalElements").asLong() : content.size();
            return new PageImpl<>(content, PageRequest.of(Math.max(pageNumber, 0), pageSize), total);
        }
    }
}