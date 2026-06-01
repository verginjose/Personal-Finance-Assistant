package com.finance.analytics.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheEvictionService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Matches your namespace from RedisConfig
    private static final String KEY_PREFIX = "finance:analytics:v1:";

    private static final String[] CACHE_NAMES = {
            "category-analytics",
            "timeline-analytics",
            "comprehensive-analytics"
    };

    public void evictForUser(UUID userId) {
        int totalEvicted = 0;

        for (String cacheName : CACHE_NAMES) {
            // Pattern: finance:analytics:v1:category-analytics::*userId=abc123*
            String pattern = KEY_PREFIX + cacheName + "::*" + userId + "*";

            Set<String> keys = redisTemplate.keys(pattern);

            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                totalEvicted += keys.size();
                log.debug("Evicted {} keys from {} for user={}",
                        keys.size(), cacheName, userId);
            }
        }

        log.info("Total cache eviction: {} keys for user={}", totalEvicted, userId);
    }
}