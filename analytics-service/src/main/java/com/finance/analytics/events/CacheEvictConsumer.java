// com.finance.analytics.events.CacheEvictConsumer.java

package com.finance.analytics.events;

import com.finance.analytics.cache.CacheEvictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictConsumer {

    private final CacheEvictionService cacheEvictionService;

    @KafkaListener(
            topics = "transaction-cache-evict",
            groupId = "analytics-cache-evict-group",
            concurrency = "3"   // 3 consumer threads — matches topic partitions
    )
    public void consume(
            @Payload CacheEvictEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Received cache evict event: user={}, operation={}, partition={}, offset={}",
                event.getUserId(), event.getOperation(), partition, offset);

        try {
            cacheEvictionService.evictForUser(event.getUserId());

            log.info("Cache evicted for user={}, operation={}",
                    event.getUserId(), event.getOperation());

        } catch (Exception e) {
            // Log but don't rethrow — stale cache is better than poison pill
            // A poison pill would cause infinite retry loop
            log.error("Cache eviction failed for user={}: {}",
                    event.getUserId(), e.getMessage());
        }
    }
}