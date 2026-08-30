package com.finance.command.events;

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
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #publish} — {@code @Async}, fire-and-forget; used by SplitService /
 *       TransactionEntryService fan-out where stale cache is acceptable.</li>
 *   <li>{@link #publishSync} — synchronous, returns {@code true} on success; used by
 *       {@link com.finance.command.service.OutboxProcessor} so it can mark events PROCESSED
 *       only after confirmed delivery.</li>
 * </ul>
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

    /**
     * Fire-and-forget async publish. Swallows exceptions so the caller's HTTP response
     * is never delayed by analytics-service unavailability.
     */
    @Async
    public void publish(UUID userId, String operation, Long transactionId) {
        try {
            restClient.post()
                    .uri(analyticsServiceUrl + "/internal/cache-evict/" + userId)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Cache evict HTTP sent: user={}, operation={}", userId, operation);
        } catch (Exception ex) {
            // Fire-and-forget: stale cache is acceptable here
            log.warn("Cache evict HTTP failed for user={}, operation={}: {}", userId, operation, ex.getMessage());
        }
    }

    /**
     * Synchronous publish — returns {@code true} if the HTTP call succeeded, {@code false}
     * otherwise. Used by the outbox processor to mark events PROCESSED only on confirmed delivery.
     */
    public boolean publishSync(UUID userId, String operation, Long transactionId) {
        try {
            restClient.post()
                    .uri(analyticsServiceUrl + "/internal/cache-evict/" + userId)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Cache evict HTTP sent (sync): user={}, operation={}", userId, operation);
            return true;
        } catch (Exception ex) {
            log.warn("Cache evict HTTP failed (sync) for user={}, operation={}: {}", userId, operation, ex.getMessage());
            return false;
        }
    }
}