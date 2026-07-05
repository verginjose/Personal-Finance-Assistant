package com.finance.command.service;

import com.finance.command.model.*;
import com.finance.command.repository.SubscriptionRepository;
import com.finance.command.repository.TransactionEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionDetectorService — Unit Tests")
class SubscriptionDetectorServiceTest {

    @Mock TransactionEntryRepository transactionRepository;
    @Mock SubscriptionRepository subscriptionRepository;

    @InjectMocks SubscriptionDetectorService service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // ── calculateNextChargeDate ───────────────────────────────────────────────

    @Test
    @DisplayName("calculateNextChargeDate: MONTHLY adds one month")
    void calculateNextChargeDate_monthly_returnsNextMonth() {
        LocalDate base = LocalDate.of(2025, 6, 1);
        assertThat(service.calculateNextChargeDate(base, RecurringPeriod.MONTHLY))
                .isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    @DisplayName("calculateNextChargeDate: WEEKLY adds 7 days")
    void calculateNextChargeDate_weekly_returnsNextWeek() {
        LocalDate base = LocalDate.of(2025, 6, 1);
        assertThat(service.calculateNextChargeDate(base, RecurringPeriod.WEEKLY))
                .isEqualTo(LocalDate.of(2025, 6, 8));
    }

    @Test
    @DisplayName("calculateNextChargeDate: YEARLY adds one year")
    void calculateNextChargeDate_yearly_returnsNextYear() {
        LocalDate base = LocalDate.of(2025, 1, 15);
        assertThat(service.calculateNextChargeDate(base, RecurringPeriod.YEARLY))
                .isEqualTo(LocalDate.of(2026, 1, 15));
    }

    // ── detectPeriodFromGaps ──────────────────────────────────────────────────

    @Test
    @DisplayName("detectPeriodFromGaps: gaps ~30 days → MONTHLY")
    void detectPeriodFromGaps_monthlyGaps_returnsMonthly() {
        List<Long> gaps = List.of(29L, 31L, 30L);
        assertThat(service.detectPeriodFromGaps(gaps)).isEqualTo(RecurringPeriod.MONTHLY);
    }

    @Test
    @DisplayName("detectPeriodFromGaps: gaps ~7 days → WEEKLY")
    void detectPeriodFromGaps_weeklyGaps_returnsWeekly() {
        List<Long> gaps = List.of(7L, 6L, 7L);
        assertThat(service.detectPeriodFromGaps(gaps)).isEqualTo(RecurringPeriod.WEEKLY);
    }

    @Test
    @DisplayName("detectPeriodFromGaps: irregular gaps → null (no pattern)")
    void detectPeriodFromGaps_irregularGaps_returnsNull() {
        List<Long> gaps = List.of(5L, 20L, 50L);
        assertThat(service.detectPeriodFromGaps(gaps)).isNull();
    }

    @Test
    @DisplayName("detectPeriodFromGaps: empty list → null")
    void detectPeriodFromGaps_emptyList_returnsNull() {
        assertThat(service.detectPeriodFromGaps(Collections.emptyList())).isNull();
    }

    // ── processRecurringTransactions ──────────────────────────────────────────

    @Test
    @DisplayName("processRecurringTransactions: saves subscription for each EXPENSE recurring entry")
    void processRecurringTransactions_expenseEntries_savesSubscriptions() {
        TransactionEntry entry = buildEntry("Netflix", new BigDecimal("649.00"), RecurringPeriod.MONTHLY);
        when(subscriptionRepository.findByUserIdAndNameAndAmount(any(), eq("Netflix"), any()))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processRecurringTransactions(List.of(entry));

        verify(subscriptionRepository, times(1)).save(any(Subscription.class));
    }

    @Test
    @DisplayName("processRecurringTransactions: skips INCOME recurring entries")
    void processRecurringTransactions_incomeEntry_skipped() {
        TransactionEntry entry = buildEntry("Salary", new BigDecimal("50000"), RecurringPeriod.MONTHLY);
        entry.setType(TransactionType.INCOME);

        service.processRecurringTransactions(List.of(entry));

        verify(subscriptionRepository, never()).save(any());
    }

    // ── deactivateSubscription ────────────────────────────────────────────────

    @Test
    @DisplayName("deactivateSubscription: sets active=false")
    void deactivateSubscription_validOwner_setsInactive() {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setUserId(userId);
        sub.setActive(true);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.deactivateSubscription(1L, userId);

        assertThat(sub.isActive()).isFalse();
        verify(subscriptionRepository).save(sub);
    }

    @Test
    @DisplayName("deactivateSubscription: throws SecurityException for wrong user")
    void deactivateSubscription_wrongUser_throwsSecurityException() {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setUserId(UUID.randomUUID()); // different user
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.deactivateSubscription(1L, userId))
                .isInstanceOf(SecurityException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TransactionEntry buildEntry(String name, BigDecimal amount, RecurringPeriod period) {
        TransactionEntry e = new TransactionEntry();
        e.setUserId(userId);
        e.setName(name);
        e.setAmount(amount);
        e.setCurrency("INR");
        e.setType(TransactionType.EXPENSE);
        e.setCategory(Category.MOVIES_AND_EVENTS);
        e.setRecurring(true);
        e.setRecurringPeriod(period);
        e.setCreatedAt(LocalDateTime.now().minusDays(5));
        return e;
    }
}
