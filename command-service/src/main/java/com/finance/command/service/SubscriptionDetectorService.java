package com.finance.command.service;

import com.finance.command.dto.SubscriptionResponse;
import com.finance.command.model.RecurringPeriod;
import com.finance.command.model.Subscription;
import com.finance.command.model.TransactionEntry;
import com.finance.command.model.TransactionType;
import com.finance.command.repository.SubscriptionRepository;
import com.finance.command.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
        refreshAllSubscriptions();
    }

    /**
     * Heal rows as soon as the service starts: a subscription whose dates were
     * frozen by an earlier bug should not stay wrong until the next 02:00 run.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void refreshOnStartup() {
        try {
            log.info("Refreshing subscriptions on startup...");
            refreshAllSubscriptions();
        } catch (RuntimeException e) {
            // Never block startup on a detection pass.
            log.warn("Startup subscription refresh failed: {}", e.getMessage(), e);
        }
    }

    private void refreshAllSubscriptions() {
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
    public SubscriptionResponse updateSubscription(Long id, UUID userId, com.finance.command.dto.UpdateSubscriptionRequest request) {
        Subscription sub = subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        if (!sub.getUserId().equals(userId)) {
            throw new SecurityException("Not authorized to modify this subscription");
        }
        sub.setName(request.getName());
        sub.setAmount(request.getAmount());
        
        if (sub.getPeriod() != request.getPeriod()) {
            // The stored next_charge_date describes the OLD cadence, so it can be
            // weeks away. Rolling it forward by one new period is what made a
            // subscription switched to DAILY show its next charge a month out.
            // Anchor on the last charge of the old cadence instead, then roll
            // forward with the new one.
            LocalDate lastCharge = sub.getNextChargeDate() != null
                    ? previousChargeDate(sub.getNextChargeDate(), sub.getPeriod())
                    : LocalDate.now();
            sub.setPeriod(request.getPeriod());
            LocalDate nextCharge = calculateNextChargeDate(lastCharge, request.getPeriod());
            sub.setNextChargeDate(nextCharge);
            int daysUntil = Math.max(0, (int) ChronoUnit.DAYS.between(LocalDate.now(), nextCharge));
            sub.setDaysUntilCharge(daysUntil);
            // Remember the user's choice: the nightly detector must not quietly
            // revert the frequency to whatever the transaction was marked as.
            sub.setPeriodLocked(true);
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
            if (entry.getType() != TransactionType.EXPENSE || !entry.isReportable()) continue;
            RecurringPeriod period = entry.getRecurringPeriod() != null
                    ? entry.getRecurringPeriod() : RecurringPeriod.MONTHLY;

            // Daily charges are refreshed here as well. Skipping them used to
            // leave existing DAILY rows frozen: the nightly job never advanced
            // their next_charge_date, so the UI kept showing an old date as
            // "tomorrow" forever.
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
                .filter(TransactionEntry::isReportable)
                .collect(Collectors.groupingBy(t -> t.getName().toLowerCase()));

        for (Map.Entry<String, List<TransactionEntry>> entry : byName.entrySet()) {
            List<TransactionEntry> group = entry.getValue();
            if (group.size() < 3) continue; // two charges can be a coincidence

            // The amounts must agree; otherwise it is the same merchant, not
            // the same subscription.
            double avg = group.stream().mapToDouble(t -> t.getAmount().doubleValue()).average().orElse(0);
            double maxDeviation = group.stream()
                    .mapToDouble(t -> Math.abs(t.getAmount().doubleValue() - avg))
                    .max().orElse(0);
            if (avg <= 0 || maxDeviation / avg > 0.10) continue;

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

            TransactionEntry last = group.getLast();
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
        if (allClose(gaps, 7, 2))   return RecurringPeriod.WEEKLY;
        if (allClose(gaps, 30, 3))  return RecurringPeriod.MONTHLY;
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

    /** The occurrence immediately before {@code nextChargeDate}, for the given cadence. */
    LocalDate previousChargeDate(LocalDate nextChargeDate, RecurringPeriod period) {
        return switch (period) {
            case DAILY   -> nextChargeDate.minusDays(1);
            case WEEKLY  -> nextChargeDate.minusWeeks(1);
            case MONTHLY -> nextChargeDate.minusMonths(1);
            case YEARLY  -> nextChargeDate.minusYears(1);
        };
    }

    /** Insert or update a subscription record. */
    private void upsertSubscription(UUID userId, String name, BigDecimal amount,
                                     String currency, RecurringPeriod period, LocalDate lastDate) {
        Optional<Subscription> existing = subscriptionRepository
                .findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, name);

        // A frequency picked in the UI outranks the detector's guess. Without
        // this, every startup/nightly pass silently undid the user's edit.
        RecurringPeriod effectivePeriod = existing
                .filter(Subscription::isPeriodLocked)
                .map(Subscription::getPeriod)
                .orElse(period);

        LocalDate nextCharge = calculateNextChargeDate(lastDate, effectivePeriod);
        int daysUntil = Math.max(0, (int) ChronoUnit.DAYS.between(LocalDate.now(), nextCharge));

        Subscription sub = existing.orElseGet(Subscription::new);
        // "Not a subscription" has to stick. The merchant keeps being detected,
        // so re-activating here would silently undo the user's decision at the
        // next detection run.
        boolean dismissed = existing.map(s -> !s.isActive()).orElse(false);

        sub.setUserId(userId);
        sub.setName(name);
        sub.setAmount(amount);
        sub.setCurrency(currency);
        sub.setPeriod(effectivePeriod);
        sub.setNextChargeDate(nextCharge);
        sub.setDaysUntilCharge(daysUntil);
        sub.setActive(!dismissed);
        sub.setLastSeenAt(LocalDateTime.now());

        subscriptionRepository.save(sub);
    }

    private SubscriptionResponse toResponse(Subscription s) {
        return new SubscriptionResponse(
                s.getId(), s.getName(), s.getAmount(), s.getCurrency(),
                s.getPeriod(), s.getNextChargeDate(), s.getDaysUntilCharge(), s.isActive());
    }
}
