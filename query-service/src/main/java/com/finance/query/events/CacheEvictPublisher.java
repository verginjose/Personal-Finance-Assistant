package com.finance.query.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.UUID;

/**
 * Publishes cache-evict events to the analytics-service via direct HTTP POST.
 * Replaces the previous Kafka-based implementation (KafkaTemplate).
 *
 * Fire-and-forget: runs async, swallows errors so the caller's HTTP response
 * is never delayed or failed due to analytics unavailability.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictPublisher {

    @Value("${analytics.service.url:http://analytics-service:8084}")
    private String analyticsServiceUrl;

    private final RestClient restClient = RestClient.create();

    public void publishForUsers(Collection<UUID> userIds, String operation, Long referenceId) {
        if (userIds == null || userIds.isEmpty()) return;
        userIds.stream().distinct().forEach(userId -> publish(userId, operation, referenceId));
    }

    @Async
    public void publish(UUID userId, String operation, Long transactionId) {
        try {
            restClient.post()
                    .uri(analyticsServiceUrl + "/internal/cache-evict/" + userId)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Cache evict HTTP sent: user={}, operation={}", userId, operation);
        } catch (Exception ex) {
            // Fire-and-forget: swallow errors — stale cache is better than blocking the caller
            log.warn("Cache evict HTTP failed for user={}, operation={}: {}", userId, operation, ex.getMessage());
        }
    }
}