package com.finance.analytics.controller;

import com.finance.analytics.cache.CacheKeyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal HTTP endpoints called by other services directly (replaces Kafka consumers).
 * These endpoints are NOT exposed through the API Gateway.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final CacheKeyRegistry cacheKeyRegistry;

    /**
     * Called by upsert-service whenever a transaction is created/updated/deleted.
     * Evicts the analytics cache for the given user so they get fresh data on next request.
     */
    @PostMapping("/cache-evict/{userId}")
    public ResponseEntity<Void> evictCache(@PathVariable UUID userId) {
        log.debug("Internal cache evict request for user={}", userId);
        try {
            cacheKeyRegistry.evictForUser(userId);
            log.info("Cache evicted for user={}", userId);
        } catch (Exception e) {
            log.error("Cache eviction failed for user={}: {}", userId, e.getMessage());
            // Return 200 anyway — stale cache is better than retries flooding this endpoint
        }
        return ResponseEntity.ok().build();
    }
}
