package com.upsertservice.events;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictPublisher {

    private static final String TOPIC = "transaction-cache-evict";

    private final KafkaTemplate<String, CacheEvictEvent> kafkaTemplate;

    public void publishForUsers(Collection<UUID> userIds, String operation, Long referenceId) {
        if (userIds == null || userIds.isEmpty()) return;
        userIds.stream().distinct().forEach(userId -> publish(userId, operation, referenceId));
    }

    public void publish(UUID userId, String operation, Long transactionId) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        CompletableFuture.runAsync(() -> {
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);
            try {
                CacheEvictEvent event = CacheEvictEvent.of(userId, operation, transactionId);

                // Use userId as partition key so same user's events go to same partition → ordered processing.
                // Wrapped in try-catch: Kafka is optional infrastructure — a missing topic or broker
                // unavailability must NEVER propagate as a 503 to the caller.
                try {
                    CompletableFuture<SendResult<String, CacheEvictEvent>> future =
                            kafkaTemplate.send(TOPIC, userId.toString(), event);

                    future.whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Cache evict event delivery failed for user={}, operation={}: {}",
                                    userId, operation, ex.getMessage());
                        } else {
                            log.debug("Cache evict event published: user={}, operation={}, partition={}",
                                    userId, operation,
                                    result.getRecordMetadata().partition());
                        }
                    });
                } catch (Exception ex) {
                    // Fire-and-forget: swallow synchronous Kafka errors (e.g. unknown topic, broker down)
                    log.warn("Could not send cache evict event for user={}, operation={}",
                            userId, operation, ex.getMessage());
                }
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        });
    }
}