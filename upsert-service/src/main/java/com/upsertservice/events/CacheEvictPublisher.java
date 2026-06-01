package com.upsertservice.events;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictPublisher {

    private static final String TOPIC = "transaction-cache-evict";

    private final KafkaTemplate<String, CacheEvictEvent> kafkaTemplate;

    public void publish(UUID userId, String operation, Long transactionId) {
        CacheEvictEvent event = CacheEvictEvent.of(userId, operation, transactionId);

        // Use userId as partition key
        // This ensures same user's events go to same partition → ordered processing
        CompletableFuture<SendResult<String, CacheEvictEvent>> future =
                kafkaTemplate.send(TOPIC, userId.toString(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // Kafka publish failed — fall back to direct eviction
                log.error("Failed to publish cache evict event for user={}, operation={}: {}",
                        userId, operation, ex.getMessage());
            } else {
                log.debug("Cache evict event published: user={}, operation={}, partition={}",
                        userId, operation,
                        result.getRecordMetadata().partition());
            }
        });
    }
}