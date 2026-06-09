package com.finance.analytics.service;

import com.finance.analytics.dto.*;
<<<<<<< Updated upstream
import com.finance.analytics.model.Category;
=======
import com.finance.analytics.model.IncomeCategory;
>>>>>>> Stashed changes
import com.finance.analytics.model.TransactionEntry;
import com.finance.analytics.model.TransactionType;
import com.finance.analytics.repository.TransactionEntryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AnalyticsService {

    private final TransactionEntryRepository repository;

    // ── Comprehensive ─────────────────────────────────────────────────────────

    @Cacheable(
            value = "comprehensive-analytics",
            key = "#request.userId + ':' + #request.timelineType + ':' + #request.cacheStartDate() + ':' + #request.cacheEndDate()"
    )
    public Map<String, Object> getComprehensiveAnalytics(AnalyticsRequest request) {
        Map<String, Object> analytics = new LinkedHashMap<>();

        BigDecimal totalIncome  = getTotalAmountByType(request, TransactionType.INCOME);
        BigDecimal totalExpense = getTotalAmountByType(request, TransactionType.EXPENSE);
        BigDecimal safeIncome   = totalIncome  != null ? totalIncome  : BigDecimal.ZERO;
        BigDecimal safeExpense  = totalExpense != null ? totalExpense : BigDecimal.ZERO;

        analytics.put("totalIncome",  safeIncome);
        analytics.put("totalExpense", safeExpense);
        analytics.put("netAmount",    safeIncome.subtract(safeExpense));

        // toBuilder() ensures any future fields on AnalyticsRequest are inherited automatically
        AnalyticsRequest incomeRequest   = request.toBuilder().transactionFilter(TransactionType.INCOME).build();
        AnalyticsRequest expenseRequest  = request.toBuilder().transactionFilter(TransactionType.EXPENSE).build();
        AnalyticsRequest timelineRequest = request.toBuilder().transactionFilter(null).build();

        // Direct calls — @Cacheable on these won't fire via this internal call path,
        // but getComprehensiveAnalytics is itself cached so this is fine.
        analytics.put("incomeByCategory",  getCategoryAnalytics(incomeRequest));
        analytics.put("expenseByCategory", getCategoryAnalytics(expenseRequest));
        analytics.put("timelineTrends",    getTimelineAnalytics(timelineRequest));

        // Hardcoded limit of 10, display only.
        // Not cached independently — the outer comprehensive cache covers this.
        Page<TransactionEntry> recentPage = repository.findByUserId(
                request.getUserId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
        analytics.put("recentTransactions", recentPage.getContent());
        analytics.put("transactionCount",   recentPage.getTotalElements());

        return analytics;
    }

    // ── Category ──────────────────────────────────────────────────────────────

    @Cacheable(
            value = "category-analytics",
            key = "#request.userId + ':' + #request.transactionFilter + ':' + #request.cacheStartDate() + ':' + #request.cacheEndDate()"
    )
    public ChartData getCategoryAnalytics(AnalyticsRequest request) {
        List<CategoryRow> rows = fetchCategoryRows(request);

        List<CategoryAnalytics> categoryData = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CategoryRow row : rows) {
            Object category   = row.getCategory() != null ? row.getCategory() : "Unknown";
            BigDecimal amount = row.getTotalAmount() != null ? row.getTotalAmount() : BigDecimal.ZERO;
            categoryData.add(new CategoryAnalytics((String)category, amount, row.getTransactionCount()));
            totalAmount = totalAmount.add(amount);
        }

        final BigDecimal total = totalAmount;
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            categoryData.forEach(data -> {
                double pct = data.getTotalAmount()
                        .divide(total, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                data.setPercentage(pct);
            });
        }

        List<String> labels = categoryData.stream()
                .map(CategoryAnalytics::getCategory)
                .collect(Collectors.toList());

        // Intentional BigDecimal → double: display only, not used in calculations
        List<Double> amounts = categoryData.stream()
                .map(data -> data.getTotalAmount().doubleValue())
                .collect(Collectors.toList());

        String title = request.getTransactionFilter() != null
                ? request.getTransactionFilter() + " Category Distribution"
                : "Category Distribution";

        return new ChartData("pie", title, labels, List.of(new ChartData.DataSet("Amount", amounts, null)));
    }

    private List<CategoryRow> fetchCategoryRows(AnalyticsRequest request) {
        UUID userId             = request.getUserId();
        String type             = request.getTransactionFilter() != null ? request.getTransactionFilter().name() : null;
        LocalDateTime startDate = request.getStartDate();
        LocalDateTime endDate   = request.getEndDate();

        if (type != null && startDate != null && endDate != null) {
            return repository.getCategoryAnalyticsByTypeAndDateRange(userId, type, startDate, endDate);
        } else if (type != null) {
            return repository.getCategoryAnalyticsByType(userId, type);
        } else if (startDate != null && endDate != null) {
            return repository.getCategoryAnalyticsByDateRange(userId, startDate, endDate);
        } else {
            return repository.getAllCategoryAnalytics(userId);
        }
    }

    // ── Timeline ──────────────────────────────────────────────────────────────

    @Cacheable(
            value = "timeline-analytics",
            key = "#request.userId + ':' + #request.timelineType + ':' + #request.transactionFilter + ':' + #request.cacheStartDate() + ':' + #request.cacheEndDate()"
    )
    public ChartData getTimelineAnalytics(AnalyticsRequest request) {
        String timelineType = request.getTimelineType() != null
                ? request.getTimelineType().toUpperCase() : "MONTHLY";

        String filterLabel = request.getTransactionFilter() != null
                ? request.getTransactionFilter() + " " : "";

        List<TimelineAnalytics> timelineData;
        String chartTitle = switch (timelineType) {
            case "DAILY" -> {
                timelineData = buildDailyTimeline(request);
                yield filterLabel + "Daily Transaction Trends";
            }
            case "YEARLY" -> {
                timelineData = buildYearlyTimeline(request);
                yield filterLabel + "Yearly Transaction Trends";
            }
            default -> {
                timelineData = buildMonthlyTimeline(request);
                yield filterLabel + "Monthly Transaction Trends";
            }
        };

        return createTimelineChart(timelineData, chartTitle, request.getTransactionFilter());
    }

    private List<TimelineAnalytics> buildDailyTimeline(AnalyticsRequest request) {
        List<DailyRow> rows = request.getStartDate() != null && request.getEndDate() != null
                ? repository.getDailyAnalyticsByDateRange(request.getUserId(), request.getStartDate(), request.getEndDate())
                : repository.getAllDailyAnalytics(request.getUserId());

        return rows.stream().map(row -> {
            LocalDate date = row.getDay().toLocalDate();
            TimelineAnalytics entry = new TimelineAnalytics(
                    date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    date.atStartOfDay(),
                    date.atTime(23, 59, 59));
            entry.setIncomeAmount(zeroIfNull(row.getIncomeAmount()));
            entry.setExpenseAmount(zeroIfNull(row.getExpenseAmount()));
            entry.setTotalTransactions(row.getTransactionCount() != null ? row.getTransactionCount() : 0L);
            return entry;
        }).collect(Collectors.toList());
    }

    private List<TimelineAnalytics> buildMonthlyTimeline(AnalyticsRequest request) {
        List<MonthlyRow> rows = request.getStartDate() != null && request.getEndDate() != null
                ? repository.getMonthlyAnalyticsByDateRange(request.getUserId(), request.getStartDate(), request.getEndDate())
                : repository.getAllMonthlyAnalytics(request.getUserId());

        return rows.stream().map(row -> {
            int year = row.getYear(), month = row.getMonth();
            LocalDateTime start = LocalDateTime.of(year, month, 1, 0, 0);
            return buildTimelineEntry(
                    String.format("%d-%02d", year, month),
                    start, start.plusMonths(1).minusSeconds(1),
                    row.getIncomeAmount(), row.getExpenseAmount(), row.getTransactionCount());
        }).collect(Collectors.toList());
    }

    private List<TimelineAnalytics> buildYearlyTimeline(AnalyticsRequest request) {
        List<YearlyRow> rows = request.getStartDate() != null && request.getEndDate() != null
                ? repository.getYearlyAnalyticsByDateRange(request.getUserId(), request.getStartDate(), request.getEndDate())
                : repository.getAllYearlyAnalytics(request.getUserId());

        return rows.stream().map(row -> {
            int year = row.getYear();
            return buildTimelineEntry(
                    String.valueOf(year),
                    LocalDateTime.of(year, 1, 1, 0, 0),
                    LocalDateTime.of(year, 12, 31, 23, 59, 59),
                    row.getIncomeAmount(), row.getExpenseAmount(), row.getTransactionCount());
        }).collect(Collectors.toList());
    }

    private TimelineAnalytics buildTimelineEntry(
            String period, LocalDateTime start, LocalDateTime end,
            BigDecimal income, BigDecimal expense, Long count) {
        TimelineAnalytics entry = new TimelineAnalytics(period, start, end);
        entry.setIncomeAmount(zeroIfNull(income));
        entry.setExpenseAmount(zeroIfNull(expense));
        entry.setTotalTransactions(count != null ? count : 0L);
        return entry;
    }

    private ChartData createTimelineChart(
            List<TimelineAnalytics> data, String title, TransactionType filter) {
        List<String> labels = data.stream()
                .map(TimelineAnalytics::getTimePeriod)
                .collect(Collectors.toList());

        List<ChartData.DataSet> datasets = new ArrayList<>();

        if (filter == null || filter == TransactionType.INCOME) {
            // Intentional BigDecimal → double: display only
            datasets.add(new ChartData.DataSet("Income",
                    data.stream().map(d -> d.getIncomeAmount().doubleValue()).collect(Collectors.toList()), null));
        }
        if (filter == null || filter == TransactionType.EXPENSE) {
            datasets.add(new ChartData.DataSet("Expense",
                    data.stream().map(d -> d.getExpenseAmount().doubleValue()).collect(Collectors.toList()), null));
        }

        return new ChartData("line", title, labels, datasets);
    }

    // ── Paginated queries ─────────────────────────────────────────────────────

    public Page<TransactionEntry> getTransactionEntriesByUserId(UUID userId, Pageable pageable) {
        return repository.findByUserId(userId, pageable);
    }

    public Page<TransactionEntry> findIncomeByCategoryAndDate(
            UUID userId, Category incomeCategory,
            LocalDateTime start, LocalDateTime end, Pageable pageable) {
        return repository.findByUserIdAndCategoryAndCreatedAtBetween(
                userId, incomeCategory, start, end, pageable);
    }

    public Page<TransactionEntry> findTransactionsByTypeAndDate(
            UUID userId, TransactionType type,
            LocalDateTime start, LocalDateTime end, Pageable pageable) {
        return repository.findByUserIdAndTypeAndCreatedAtBetween(
                userId, type, start, end, pageable);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal getTotalAmountByType(AnalyticsRequest request, TransactionType type) {
        UUID userId             = request.getUserId();
        LocalDateTime startDate = request.getStartDate();
        LocalDateTime endDate   = request.getEndDate();

        return (startDate != null && endDate != null)
                ? repository.getTotalAmountByTypeAndDateRange(userId, type.name(), startDate, endDate)
                : repository.getTotalAmountByType(userId, type.name());
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}