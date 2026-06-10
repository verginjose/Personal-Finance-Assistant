package com.upsertservice.service;

import com.upsertservice.events.CacheEvictPublisher;
import com.upsertservice.model.OutboxEvent;
import com.upsertservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxEventRepository repository;
    private final CacheEvictPublisher cacheEvictPublisher;

    @Scheduled(fixedDelayString = "${outbox.processor.delay:5000}")
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> events = repository.findByStatusOrderByCreatedAtAsc(OutboxEvent.EventStatus.PENDING);
        if (events.isEmpty()) {
            return;
        }

        log.info("Processing {} outbox events...", events.size());

        for (OutboxEvent event : events) {
            try {
                cacheEvictPublisher.publish(event.getUserId(), event.getEventType(), event.getEntityId());
                event.setStatus(OutboxEvent.EventStatus.PROCESSED);
            } catch (Exception e) {
                log.error("Failed to process outbox event id={}", event.getId(), e);
                // Depending on requirements, we can increment a retry counter and set to FAILED if > maxRetries
                event.setStatus(OutboxEvent.EventStatus.FAILED);
            }
        }

        repository.saveAll(events);
    }
}
