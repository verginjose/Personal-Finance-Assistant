package com.upsertservice.service;

import com.upsertservice.model.TransactionEntry;
import com.upsertservice.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringTransactionScheduler {

    private final TransactionEntryRepository repository;
    private final TransactionEntryService transactionEntryService;

    // Run every hour. ShedLock ensures only one instance executes this at a time.
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "processRecurringTransactions", lockAtLeastFor = "5m", lockAtMostFor = "15m")
    @Transactional
    public void processRecurringTransactions() {
        LocalDateTime now = LocalDateTime.now();
        log.info("Starting processing of recurring transactions at {}", now);

        // Find all recurring entries where next_run_date is less than or equal to now
        List<TransactionEntry> dueEntries = repository.findByRecurringTrueAndDeletedAtIsNullAndNextRunDateLessThanEqual(now);
        
        if (dueEntries.isEmpty()) {
            log.info("No recurring transactions due for processing.");
            return;
        }

        log.info("Found {} recurring transactions to process.", dueEntries.size());

        for (TransactionEntry original : dueEntries) {
            try {
                // 1. Create a duplicate transaction for the current period
                TransactionEntry newEntry = new TransactionEntry(
                        original.getUserId(),
                        original.getName(),
                        original.getAmount(),
                        original.getType(),
                        original.getCurrency()
                );
                newEntry.setCategory(original.getCategory());
                newEntry.setDescription("Auto-generated: " + original.getDescription());
                newEntry.setRecurring(false); // The generated instance is not recurring itself
                newEntry.setCreatedAt(original.getNextRunDate());
                
                repository.save(newEntry);
                
                // 2. Advance the original transaction's nextRunDate
                LocalDateTime nextRun = TransactionEntryService.calculateNextRunDate(
                        original.getNextRunDate(), original.getRecurringPeriod());
                original.setNextRunDate(nextRun);
                repository.save(original);

                log.info("Successfully processed recurring transaction {}. Next run: {}", original.getId(), nextRun);
            } catch (Exception e) {
                log.error("Failed to process recurring transaction id={}", original.getId(), e);
            }
        }
    }
}
