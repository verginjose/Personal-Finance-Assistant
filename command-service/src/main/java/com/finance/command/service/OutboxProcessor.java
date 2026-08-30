package com.finance.command.service;

import com.finance.command.events.CacheEvictPublisher;
import com.finance.command.model.OutboxEvent;
import com.finance.command.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    @SchedulerLock(
            name = "processOutboxEvents",
            lockAtLeastFor = "2s",
            lockAtMostFor = "1m"
    )
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> events = repository.findByStatusOrderByCreatedAtAsc(OutboxEvent.EventStatus.PENDING);
        if (events.isEmpty()) {
            return;
        }

        log.info("Processing {} outbox events...", events.size());

        for (OutboxEvent event : events) {
            // publishSync returns true only when the HTTP call to query-service returned 2xx.
            // Marking PROCESSED on a false (or swallowed exception) was the old bug — events
            // would be permanently lost even when the query-service was down.
            boolean delivered = cacheEvictPublisher.publishSync(
                    event.getUserId(), event.getEventType(), event.getEntityId());

            if (delivered) {
                event.setStatus(OutboxEvent.EventStatus.PROCESSED);
            } else {
                log.warn("Outbox event id={} delivery failed — will retry on next poll", event.getId());
                // Leave status as PENDING so the next scheduler tick retries it.
                // Optionally increment a retry counter here to eventually dead-letter.
            }
        }

        repository.saveAll(events);
    }
}
