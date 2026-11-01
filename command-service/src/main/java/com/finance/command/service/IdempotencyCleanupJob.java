package com.finance.command.service;

import com.finance.command.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Idempotency keys used to live in Redis with a 24h TTL. They are now rows in
 * {@code finance.idempotency_records} (the unique constraint is what arbitrates
 * concurrent double submits), so something has to expire them. Keep them for
 * the same 24h the client retry window expects, then purge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private static final int RETENTION_HOURS = 24;

    private final IdempotencyRecordRepository repository;

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    @SchedulerLock(name = "purgeIdempotencyRecords", lockAtLeastFor = "1m", lockAtMostFor = "30m")
    @Transactional
    public void purgeExpiredKeys() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        int deleted = repository.deleteOlderThan(cutoff);
        log.info("Purged {} idempotency records older than {}", deleted, cutoff);
    }
}
