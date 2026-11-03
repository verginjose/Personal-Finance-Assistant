package com.finance.command.service;

import com.finance.command.cache.CacheKeyRegistry;
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
    private final CacheKeyRegistry cacheKeyRegistry;

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
            // Evict directly in Redis — both services share the instance and the
            // registry Set. This used to be an HTTP call to query-service's
            // /internal/cache-evict; that hop is gone, so the retry no longer
            // depends on the other service being up.
            boolean delivered = cacheKeyRegistry.evictForUser(event.getUserId());

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
