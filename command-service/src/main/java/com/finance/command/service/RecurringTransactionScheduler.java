package com.finance.command.service;

import com.finance.command.model.TransactionEntry;
import com.finance.command.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringTransactionScheduler {

    private final TransactionEntryRepository repository;
    private final com.finance.command.repository.OutboxEventRepository outboxEventRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    // Run every hour. ShedLock ensures only one instance executes this at a time.
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "processRecurringTransactions", lockAtLeastFor = "5m", lockAtMostFor = "15m")
    public void processRecurringTransactions() {
        LocalDateTime now = LocalDateTime.now();
        log.info("Starting processing of recurring transactions at {}", now);

        int page = 0;
        int size = 500;
        int totalProcessed = 0;

        while (true) {
            Page<TransactionEntry> duePage = repository.findByRecurringTrueAndDeletedAtIsNullAndNextRunDateLessThanEqual(
                    now, PageRequest.of(page, size));

            if (duePage.isEmpty()) {
                break;
            }

            log.info("Processing page {} of recurring transactions ({} items).", page, duePage.getNumberOfElements());

            for (TransactionEntry original : duePage.getContent()) {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        // Calculate missed periods and aggregate amount
                        LocalDateTime tempDate = original.getNextRunDate();
                        int missedPeriods = 0;
                        
                        while (tempDate.isBefore(now) || tempDate.isEqual(now)) {
                            missedPeriods++;
                            tempDate = TransactionEntryService.calculateNextRunDate(tempDate, original.getRecurringPeriod());
                            
                            // Failsafe to prevent infinite loop
                            if (missedPeriods > 1000) {
                                log.warn("Excessive missed periods for transaction {}. Breaking loop.", original.getId());
                                break;
                            }
                        }
                        
                        java.math.BigDecimal aggregatedAmount = original.getAmount().multiply(java.math.BigDecimal.valueOf(missedPeriods));

                        // 1. Create a duplicate transaction for the current period
                        TransactionEntry newEntry = new TransactionEntry(
                                original.getUserId(),
                                original.getName(),
                                aggregatedAmount,
                                original.getType(),
                                original.getCurrency()
                        );
                        newEntry.setCategory(original.getCategory());
                        newEntry.setDescription(missedPeriods > 1 ? 
                                "Auto-generated (Aggregated " + missedPeriods + " missed periods): " + original.getDescription() :
                                "Auto-generated: " + original.getDescription());
                        newEntry.setRecurring(false); // The generated instance is not recurring itself
                        newEntry.setCreatedAt(now);
                        
                        repository.save(newEntry);
                        
                        com.finance.command.model.OutboxEvent event = new com.finance.command.model.OutboxEvent();
                        event.setUserId(newEntry.getUserId());
                        event.setEventType("CREATE");
                        event.setEntityId(newEntry.getId());
                        outboxEventRepository.save(event);
                        
                        // 2. Advance the original transaction's nextRunDate
                        original.setNextRunDate(tempDate);
                        repository.save(original);
                        
                        // 3. Push notification
                        notificationService.sendNotification(original.getUserId(), java.util.Map.of(
                            "status", "INFO",
                            "message", "Recurring transaction '" + original.getName() + "' of ₹" + aggregatedAmount + " was logged automatically.",
                            "event", "recurring-processed"
                        ));

                        log.info("Successfully processed recurring transaction {}. Missed periods: {}. Next run: {}", original.getId(), missedPeriods, tempDate);
                    });
                    totalProcessed++;
                } catch (Exception e) {
                    log.error("Failed to process recurring transaction id={}", original.getId(), e);
                }
            }

            if (!duePage.hasNext()) {
                break;
            }
            page++;
        }

        log.info("Finished processing {} recurring transactions.", totalProcessed);
    }
}
