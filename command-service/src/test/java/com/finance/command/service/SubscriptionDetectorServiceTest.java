package com.finance.command.service;

import com.finance.command.dto.UpdateSubscriptionRequest;
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
        LocalDate base = LocalDate.now().plusDays(1);
        assertThat(service.calculateNextChargeDate(base, RecurringPeriod.MONTHLY))
                .isEqualTo(base.plusMonths(1));
    }

    @Test
    @DisplayName("calculateNextChargeDate: WEEKLY adds 7 days")
    void calculateNextChargeDate_weekly_returnsNextWeek() {
        LocalDate base = LocalDate.now().plusDays(1);
        assertThat(service.calculateNextChargeDate(base, RecurringPeriod.WEEKLY))
                .isEqualTo(base.plusWeeks(1));
    }

    @Test
    @DisplayName("calculateNextChargeDate: YEARLY adds one year")
    void calculateNextChargeDate_yearly_returnsNextYear() {
        LocalDate base = LocalDate.now().plusDays(1);
        assertThat(service.calculateNextChargeDate(base, RecurringPeriod.YEARLY))
                .isEqualTo(base.plusYears(1));
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
        when(subscriptionRepository.findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(any(), eq("Netflix")))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processRecurringTransactions(List.of(entry));

        verify(subscriptionRepository, times(1)).save(any(Subscription.class));
    }

    @Test
    @DisplayName("processRecurringTransactions: refreshes a DAILY subscription's dates")
    void processRecurringTransactions_dailyEntry_advancesNextChargeDate() {
        TransactionEntry entry = buildEntry("Amul milk delivery", new BigDecimal("64.00"), RecurringPeriod.DAILY);

        // Row frozen by the old job: date in the past, countdown still 1.
        Subscription existing = new Subscription();
        existing.setId(9L);
        existing.setUserId(userId);
        existing.setName("Amul milk delivery");
        existing.setAmount(new BigDecimal("55.96"));
        existing.setNextChargeDate(LocalDate.now().minusDays(1));
        existing.setDaysUntilCharge(1);
        when(subscriptionRepository.findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(any(), eq("Amul milk delivery")))
                .thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processRecurringTransactions(List.of(entry));

        assertThat(existing.getNextChargeDate()).isAfter(LocalDate.now());
        assertThat(existing.getDaysUntilCharge()).isGreaterThanOrEqualTo(1);
        // The price is updated in place instead of adding a duplicate row.
        assertThat(existing.getAmount()).isEqualByComparingTo("64.00");
    }

    @Test
    @DisplayName("processRecurringTransactions: a dismissed subscription stays dismissed")
    void processRecurringTransactions_dismissedSubscription_staysInactive() {
        TransactionEntry entry = buildEntry("Netflix", new BigDecimal("649.00"), RecurringPeriod.MONTHLY);

        Subscription dismissed = new Subscription();
        dismissed.setId(7L);
        dismissed.setUserId(userId);
        dismissed.setName("Netflix");
        dismissed.setAmount(new BigDecimal("649.00"));
        dismissed.setActive(false);
        when(subscriptionRepository.findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(any(), eq("Netflix")))
                .thenReturn(Optional.of(dismissed));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processRecurringTransactions(List.of(entry));

        assertThat(dismissed.isActive()).isFalse();
    }

    @Test
    @DisplayName("processRecurringTransactions: skips INCOME recurring entries")
    void processRecurringTransactions_incomeEntry_skipped() {
        TransactionEntry entry = buildEntry("Salary", new BigDecimal("50000"), RecurringPeriod.MONTHLY);
        entry.setType(TransactionType.INCOME);

        service.processRecurringTransactions(List.of(entry));

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("processRecurringTransactions: a manual frequency edit survives detection")
    void processRecurringTransactions_lockedPeriod_notOverwritten() {
        TransactionEntry entry = buildEntry("PAal", new BigDecimal("55.00"), RecurringPeriod.MONTHLY);
        entry.setCreatedAt(LocalDateTime.now().minusDays(2));

        Subscription edited = new Subscription();
        edited.setId(5L);
        edited.setUserId(userId);
        edited.setName("PAal");
        edited.setAmount(new BigDecimal("55.00"));
        edited.setPeriod(RecurringPeriod.DAILY);
        edited.setPeriodLocked(true);
        edited.setNextChargeDate(LocalDate.now().plusDays(1));
        when(subscriptionRepository.findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(any(), eq("PAal")))
                .thenReturn(Optional.of(edited));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processRecurringTransactions(List.of(entry));

        // The transaction still says MONTHLY, but the user's DAILY choice wins.
        assertThat(edited.getPeriod()).isEqualTo(RecurringPeriod.DAILY);
        assertThat(edited.getNextChargeDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    @DisplayName("processRecurringTransactions: an unlocked period follows the transaction")
    void processRecurringTransactions_unlockedPeriod_followsTransaction() {
        TransactionEntry entry = buildEntry("Netflix", new BigDecimal("649.00"), RecurringPeriod.WEEKLY);
        entry.setCreatedAt(LocalDateTime.now().minusDays(2));

        Subscription existing = new Subscription();
        existing.setId(6L);
        existing.setUserId(userId);
        existing.setName("Netflix");
        existing.setAmount(new BigDecimal("649.00"));
        existing.setPeriod(RecurringPeriod.MONTHLY);
        existing.setNextChargeDate(LocalDate.now().plusMonths(1));
        when(subscriptionRepository.findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(any(), eq("Netflix")))
                .thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processRecurringTransactions(List.of(entry));

        assertThat(existing.getPeriod()).isEqualTo(RecurringPeriod.WEEKLY);
    }

    // ── updateSubscription ────────────────────────────────────────────────────

    @Test
    @DisplayName("updateSubscription: MONTHLY -> DAILY charges tomorrow, not next month")
    void updateSubscription_monthlyToDaily_recomputesFromLastCharge() {
        LocalDate lastCharge = LocalDate.now().minusDays(2);

        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setUserId(userId);
        sub.setName("PAal");
        sub.setAmount(new BigDecimal("55.00"));
        sub.setPeriod(RecurringPeriod.MONTHLY);
        // Old cadence: the next charge is almost a month away.
        sub.setNextChargeDate(lastCharge.plusMonths(1));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateSubscriptionRequest request = new UpdateSubscriptionRequest();
        request.setName("PAal");
        request.setAmount(new BigDecimal("55.00"));
        request.setPeriod(RecurringPeriod.DAILY);

        service.updateSubscription(1L, userId, request);

        assertThat(sub.getPeriod()).isEqualTo(RecurringPeriod.DAILY);
        assertThat(sub.getNextChargeDate()).isEqualTo(LocalDate.now().plusDays(1));
        assertThat(sub.getDaysUntilCharge()).isEqualTo(1);
        assertThat(sub.isPeriodLocked()).isTrue();
    }

    @Test
    @DisplayName("updateSubscription: an unchanged period leaves the lock alone")
    void updateSubscription_samePeriod_doesNotLock() {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setUserId(userId);
        sub.setName("Netflix");
        sub.setAmount(new BigDecimal("649.00"));
        sub.setPeriod(RecurringPeriod.MONTHLY);
        sub.setNextChargeDate(LocalDate.now().plusDays(10));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateSubscriptionRequest request = new UpdateSubscriptionRequest();
        request.setName("Netflix");
        request.setAmount(new BigDecimal("699.00"));
        request.setPeriod(RecurringPeriod.MONTHLY);

        service.updateSubscription(1L, userId, request);

        assertThat(sub.getNextChargeDate()).isEqualTo(LocalDate.now().plusDays(10));
        assertThat(sub.isPeriodLocked()).isFalse();
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
