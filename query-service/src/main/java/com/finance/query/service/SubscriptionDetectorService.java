package com.finance.query.service;

import com.finance.query.dto.SubscriptionResponse;
import com.finance.query.model.RecurringPeriod;
import com.finance.query.model.Subscription;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.SubscriptionRepository;
import com.finance.query.repository.TransactionEntryRepository;
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

    public List<SubscriptionResponse> getSubscriptionsForUser(UUID userId) {
        List<Subscription> subs = subscriptionRepository
                .findByUserIdAndActiveTrueOrderByDaysUntilChargeAsc(userId);
        return subs.stream().map(this::toResponse).collect(Collectors.toList());
    }



    private SubscriptionResponse toResponse(Subscription s) {
        return new SubscriptionResponse(
                s.getId(), s.getName(), s.getAmount(), s.getCurrency(),
                s.getPeriod(), s.getNextChargeDate(), s.getDaysUntilCharge(), s.isActive());
    }
}
