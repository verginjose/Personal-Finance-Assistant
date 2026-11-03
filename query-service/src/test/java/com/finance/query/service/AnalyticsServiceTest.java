package com.finance.query.service;

import com.finance.query.dto.AnalyticsRequest;
import com.finance.query.dto.ChartData;
import com.finance.query.dto.DailyRow;
import com.finance.query.repository.TransactionEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsService — timeline filters")
class AnalyticsServiceTest {

    @Mock TransactionEntryRepository repository;

    private AnalyticsService service() {
        return new AnalyticsService(repository);
    }

    @Test
    @DisplayName("WEEKLY groups days into Monday-first ISO weeks")
    void weeklyTimeline_groupsByIsoWeek() {
        DailyRow monday = day("2026-11-02", "1000", "0", 1L);
        DailyRow tuesday = day("2026-11-03", "0", "200", 1L);
        when(repository.getDailyAnalytics(eq(USER), any(), any(), eq(330)))
                .thenReturn(List.of(monday, tuesday));

        ChartData chart = service().getTimelineAnalytics(request("WEEKLY"));

        assertThat(chart.getLabels()).containsExactly("2026-W45");
        assertThat(chart.getDatasets().get(0).getData()).containsExactly(1000.0);
        assertThat(chart.getDatasets().get(1).getData()).containsExactly(200.0);
    }

    @Test
    @DisplayName("local date bounds are shifted to UTC using tzOffsetMinutes")
    void dateRange_isConvertedFromUserTimezoneToUtc() {
        when(repository.getDailyAnalytics(eq(USER), any(), any(), eq(330)))
                .thenReturn(List.of());

        AnalyticsRequest request = AnalyticsRequest.builder()
                .userId(USER)
                .timelineType("DAILY")
                .startDate(LocalDateTime.of(2026, 11, 1, 0, 0))
                .endDate(LocalDateTime.of(2026, 11, 30, 23, 59, 59, 999_000_000))
                .tzOffsetMinutes(330)
                .build();

        service().getTimelineAnalytics(request);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).getDailyAnalytics(eq(USER), start.capture(), end.capture(), eq(330));

        // IST midnight (2026-11-01 00:00 +05:30) is 2026-10-31 18:30 UTC.
        assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 31, 18, 30));
        assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 11, 30, 18, 29, 59, 999_000_000));
    }

    private static final UUID USER = UUID.randomUUID();

    private AnalyticsRequest request(String timelineType) {
        return AnalyticsRequest.builder()
                .userId(USER)
                .timelineType(timelineType)
                .tzOffsetMinutes(330)
                .build();
    }

    private DailyRow day(String date, String income, String expense, Long count) {
        DailyRow row = org.mockito.Mockito.mock(DailyRow.class);
        when(row.getDay()).thenReturn(Date.valueOf(date));
        when(row.getIncomeAmount()).thenReturn(new BigDecimal(income));
        when(row.getExpenseAmount()).thenReturn(new BigDecimal(expense));
        when(row.getTransactionCount()).thenReturn(count);
        return row;
    }
}
