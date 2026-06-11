package com.upsertservice.service;

import com.upsertservice.dto.SubscriptionResponse;
import com.upsertservice.model.RecurringPeriod;
import com.upsertservice.model.Subscription;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.repository.SubscriptionRepository;
import com.upsertservice.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionDetectorService {

    private final TransactionEntryRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;

    // ── Scheduled daily detection job ────────────────────────────────────────

    /**
     * Runs every day at 02:00 UTC. Scans all users' recurring transactions
     * and pattern-detected subscriptions, then refreshes the subscriptions table.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    public void runDailyDetection() {
        log.info("Starting daily subscription detection job...");
        // Detect from all recurring=true transactions in the system
        List<TransactionEntry> allRecurring = transactionRepository
                .findAllByRecurringTrueAndDeletedAtIsNull();
        processRecurringTransactions(allRecurring);
        log.info("Subscription detection complete. Processed {} recurring transactions.", allRecurring.size());
    }

    // ── Per-user detection (on-demand) ────────────────────────────────────────

    @Transactional
    public List<SubscriptionResponse> detectAndGetForUser(UUID userId) {
        // 1. From explicitly marked recurring transactions
        List<TransactionEntry> recurring = transactionRepository
                .findByUserIdAndRecurringTrueAndDeletedAtIsNull(userId);
        processRecurringTransactions(recurring);

        // 2. Pattern-based detection from the last 90 days
        LocalDateTime since = LocalDateTime.now().minusDays(90);
        List<TransactionEntry> recent = transactionRepository
                .findByUserIdAndTypeAndCreatedAtAfterAndDeletedAtIsNull(userId, TransactionType.EXPENSE, since);
        detectPatternBasedSubscriptions(userId, recent);

        // 3. Return current active subscriptions
        return getSubscriptionsForUser(userId);
    }

    public List<SubscriptionResponse> getSubscriptionsForUser(UUID userId) {
        List<Subscription> subs = subscriptionRepository
                .findByUserIdAndActiveTrueOrderByDaysUntilChargeAsc(userId);
        return subs.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deactivateSubscription(Long id, UUID userId) {
        Subscription sub = subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        if (!sub.getUserId().equals(userId)) {
            throw new SecurityException("Not authorized to modify this subscription");
        }
        sub.setActive(false);
        subscriptionRepository.save(sub);
        log.info("Subscription {} deactivated by user {}", id, userId);
    }

    @Transactional
    public SubscriptionResponse updateSubscription(Long id, UUID userId, com.upsertservice.dto.UpdateSubscriptionRequest request) {
        Subscription sub = subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        if (!sub.getUserId().equals(userId)) {
            throw new SecurityException("Not authorized to modify this subscription");
        }
        sub.setName(request.getName());
        sub.setAmount(request.getAmount());
        
        if (sub.getPeriod() != request.getPeriod()) {
            sub.setPeriod(request.getPeriod());
            LocalDate nextCharge = calculateNextChargeDate(sub.getNextChargeDate() != null ? sub.getNextChargeDate() : LocalDate.now(), request.getPeriod());
            sub.setNextChargeDate(nextCharge);
            int daysUntil = Math.max(0, (int) ChronoUnit.DAYS.between(LocalDate.now(), nextCharge));
            sub.setDaysUntilCharge(daysUntil);
        }
        
        subscriptionRepository.save(sub);
        log.info("Subscription {} updated by user {}", id, userId);
        return toResponse(sub);
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    /**
     * Creates or refreshes Subscription records from transactions explicitly marked recurring.
     */
    void processRecurringTransactions(List<TransactionEntry> recurringEntries) {
        for (TransactionEntry entry : recurringEntries) {
            if (entry.getType() != TransactionType.EXPENSE) continue;
            RecurringPeriod period = entry.getRecurringPeriod() != null
                    ? entry.getRecurringPeriod() : RecurringPeriod.MONTHLY;

            upsertSubscription(entry.getUserId(), entry.getName(), entry.getAmount(),
                    entry.getCurrency(), period, entry.getCreatedAt().toLocalDate());
        }
    }

    /**
     * Detects subscription patterns: if the same (name, ~amount) appears ≥2 times
     * within a consistent interval, treat it as a subscription.
     */
    void detectPatternBasedSubscriptions(UUID userId, List<TransactionEntry> transactions) {
        // Group by name (case-insensitive)
        Map<String, List<TransactionEntry>> byName = transactions.stream()
                .collect(Collectors.groupingBy(t -> t.getName().toLowerCase()));

        for (Map.Entry<String, List<TransactionEntry>> entry : byName.entrySet()) {
            List<TransactionEntry> group = entry.getValue();
            if (group.size() < 2) continue;

            // Sort by date, then compute gaps
            group.sort(Comparator.comparing(TransactionEntry::getCreatedAt));
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < group.size(); i++) {
                long days = ChronoUnit.DAYS.between(
                        group.get(i - 1).getCreatedAt(), group.get(i).getCreatedAt());
                gaps.add(days);
            }

            RecurringPeriod period = detectPeriodFromGaps(gaps);
            if (period == null) continue; // not a consistent pattern

            TransactionEntry last = group.get(group.size() - 1);
            upsertSubscription(userId, last.getName(), last.getAmount(),
                    last.getCurrency(), period, last.getCreatedAt().toLocalDate());
        }
    }

    /**
     * Detects if a list of day-gaps corresponds to WEEKLY (≈7), MONTHLY (≈30), or YEARLY (≈365).
     * Allows ±5 day tolerance.
     */
    RecurringPeriod detectPeriodFromGaps(List<Long> gaps) {
        if (gaps.isEmpty()) return null;
        double avg = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        if (allClose(gaps, 7, 5))   return RecurringPeriod.WEEKLY;
        if (allClose(gaps, 30, 5))  return RecurringPeriod.MONTHLY;
        if (allClose(gaps, 365, 10)) return RecurringPeriod.YEARLY;
        return null;
    }

    private boolean allClose(List<Long> gaps, long target, long tolerance) {
        return gaps.stream().allMatch(g -> Math.abs(g - target) <= tolerance);
    }

    /** Calculate next charge date from last transaction date and period, ensuring it is in the future. */
    LocalDate calculateNextChargeDate(LocalDate lastDate, RecurringPeriod period) {
        if (lastDate == null || period == null) return LocalDate.now();
        LocalDate nextCharge = lastDate;
        LocalDate today = LocalDate.now();
        
        do {
            nextCharge = switch (period) {
                case DAILY   -> nextCharge.plusDays(1);
                case WEEKLY  -> nextCharge.plusWeeks(1);
                case MONTHLY -> nextCharge.plusMonths(1);
                case YEARLY  -> nextCharge.plusYears(1);
            };
        } while (nextCharge.isBefore(today) || nextCharge.isEqual(today));
        
        return nextCharge;
    }

    /** Insert or update a subscription record. */
    private void upsertSubscription(UUID userId, String name, BigDecimal amount,
                                     String currency, RecurringPeriod period, LocalDate lastDate) {
        LocalDate nextCharge = calculateNextChargeDate(lastDate, period);
        int daysUntil = Math.max(0, (int) ChronoUnit.DAYS.between(LocalDate.now(), nextCharge));

        Optional<Subscription> existing = subscriptionRepository
                .findByUserIdAndNameAndAmount(userId, name, amount);

        Subscription sub = existing.orElseGet(Subscription::new);
        sub.setUserId(userId);
        sub.setName(name);
        sub.setAmount(amount);
        sub.setCurrency(currency);
        sub.setPeriod(period);
        sub.setNextChargeDate(nextCharge);
        sub.setDaysUntilCharge(daysUntil);
        sub.setActive(true);
        sub.setLastSeenAt(LocalDateTime.now());

        subscriptionRepository.save(sub);
    }

    private SubscriptionResponse toResponse(Subscription s) {
        return new SubscriptionResponse(
                s.getId(), s.getName(), s.getAmount(), s.getCurrency(),
                s.getPeriod(), s.getNextChargeDate(), s.getDaysUntilCharge(), s.isActive());
    }
}
