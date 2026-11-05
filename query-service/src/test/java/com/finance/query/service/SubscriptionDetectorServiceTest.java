package com.finance.query.service;

import com.finance.query.dto.SubscriptionResponse;
import com.finance.query.model.RecurringPeriod;
import com.finance.query.model.Subscription;
import com.finance.query.repository.SubscriptionRepository;
import com.finance.query.repository.TransactionEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuerySubscriptionService — countdown is derived at read time")
class SubscriptionDetectorServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock TransactionEntryRepository transactionEntryRepository;

    private SubscriptionDetectorService service() {
        return new SubscriptionDetectorService(transactionEntryRepository, subscriptionRepository);
    }

    @Test
    @DisplayName("stored daysUntilCharge is ignored in favour of the date")
    void staleStoredCountdown_isRecomputedFromDate() {
        // A row frozen by the old nightly job: date a day ago, but still "1 day".
        Subscription sub = subscription(1L, "Amul milk delivery", RecurringPeriod.DAILY,
                LocalDate.now(ZoneOffset.UTC).minusDays(1), 1);
        when(subscriptionRepository.findByUserIdAndActiveTrueOrderByNextChargeDateAsc(USER))
                .thenReturn(List.of(sub));

        List<SubscriptionResponse> result = service().getSubscriptionsForUser(USER, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDaysUntilCharge()).isEqualTo(-1);
    }

    @Test
    @DisplayName("today is the caller's local date, not the server's")
    void countdown_usesCallerTimezone() {
        LocalDate due = LocalDate.now(ZoneOffset.UTC).plusDays(3);
        Subscription sub = subscription(2L, "Cult.fit", RecurringPeriod.MONTHLY, due, 5);
        when(subscriptionRepository.findByUserIdAndActiveTrueOrderByNextChargeDateAsc(USER))
                .thenReturn(List.of(sub));

        int istDays = service().getSubscriptionsForUser(USER, 330).get(0).getDaysUntilCharge();
        int sfDays = service().getSubscriptionsForUser(USER, -480).get(0).getDaysUntilCharge();

        assertThat(istDays).isEqualTo((int) ChronoUnit.DAYS.between(
                LocalDate.now(ZoneOffset.ofHoursMinutes(5, 30)), due));
        assertThat(sfDays).isEqualTo((int) ChronoUnit.DAYS.between(
                LocalDate.now(ZoneOffset.ofHours(-8)), due));
    }

    @Test
    @DisplayName("results are ordered by the recomputed countdown")
    void results_sortedByLiveCountdown() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Subscription later = subscription(3L, "Later", RecurringPeriod.MONTHLY, today.plusDays(9), 0);
        Subscription sooner = subscription(4L, "Sooner", RecurringPeriod.WEEKLY, today.plusDays(2), 99);
        when(subscriptionRepository.findByUserIdAndActiveTrueOrderByNextChargeDateAsc(USER))
                .thenReturn(List.of(later, sooner));

        List<SubscriptionResponse> result = service().getSubscriptionsForUser(USER, 0);

        assertThat(result).extracting(SubscriptionResponse::getName)
                .containsExactly("Sooner", "Later");
    }

    private Subscription subscription(Long id, String name, RecurringPeriod period,
                                      LocalDate nextCharge, int storedDays) {
        Subscription sub = new Subscription();
        sub.setId(id);
        sub.setUserId(USER);
        sub.setName(name);
        sub.setAmount(new BigDecimal("100.00"));
        sub.setCurrency("INR");
        sub.setPeriod(period);
        sub.setNextChargeDate(nextCharge);
        sub.setDaysUntilCharge(storedDays);
        sub.setActive(true);
        return sub;
    }
}
