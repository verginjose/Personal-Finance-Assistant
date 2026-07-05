package com.finance.query.controller;

import com.finance.query.cache.CacheKeyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalCacheController {

    private final CacheKeyRegistry cacheKeyRegistry;

    @PostMapping("/cache-evict/{userId}")
    public ResponseEntity<Void> evictUserCache(@PathVariable UUID userId) {
        log.info("Received internal cache evict request for user: {}", userId);
        cacheKeyRegistry.evictForUser(userId);
        return ResponseEntity.ok().build();
    }
}
