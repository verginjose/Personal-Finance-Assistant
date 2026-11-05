package com.finance.query.service;

import com.finance.query.dto.SubscriptionResponse;
import com.finance.query.model.Subscription;
import com.finance.query.repository.SubscriptionRepository;
import com.finance.query.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionDetectorService {

    /** Keeps a hostile tzOffsetMinutes from blowing up ZoneOffset. */
    private static final int MAX_OFFSET_MINUTES = 18 * 60;

    private final TransactionEntryRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Reads the stored subscriptions and computes the countdown at read time.
     * days_until_charge in the table is only a snapshot written by the nightly
     * job, so rendering it as a live value made the UI lie between runs.
     *
     * @param tzOffsetMinutes minutes east of UTC for the caller's timezone
     *                        (IST = 330); "today" is their local date.
     */
    public List<SubscriptionResponse> getSubscriptionsForUser(UUID userId, Integer tzOffsetMinutes) {
        LocalDate today = LocalDate.now(zoneOffset(tzOffsetMinutes));
        return subscriptionRepository
                .findByUserIdAndActiveTrueOrderByNextChargeDateAsc(userId)
                .stream()
                .map(s -> toResponse(s, today))
                .sorted(Comparator.comparingInt(SubscriptionResponse::getDaysUntilCharge)
                        .thenComparing(SubscriptionResponse::getName))
                .collect(Collectors.toList());
    }

    private ZoneOffset zoneOffset(Integer tzOffsetMinutes) {
        int minutes = tzOffsetMinutes != null ? tzOffsetMinutes : 0;
        minutes = Math.max(-MAX_OFFSET_MINUTES, Math.min(MAX_OFFSET_MINUTES, minutes));
        return ZoneOffset.ofTotalSeconds(minutes * 60);
    }

    private SubscriptionResponse toResponse(Subscription s, LocalDate today) {
        int daysUntil = s.getNextChargeDate() == null
                ? 0
                : (int) ChronoUnit.DAYS.between(today, s.getNextChargeDate());
        return new SubscriptionResponse(
                s.getId(), s.getName(), s.getAmount(), s.getCurrency(),
                s.getPeriod(), s.getNextChargeDate(), daysUntil, s.isActive());
    }
}
