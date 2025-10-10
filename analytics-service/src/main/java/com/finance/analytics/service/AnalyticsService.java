package com.finance.analytics.service;

import com.finance.analytics.dto.AnalyticsRequest;
import com.finance.analytics.dto.CategoryAnalytics;
import com.finance.analytics.dto.ChartData;
import com.finance.analytics.dto.TimelineAnalytics;
import com.finance.analytics.model.IncomeCategory;
import com.finance.analytics.model.TransactionEntry;
import com.finance.analytics.model.TransactionType;
import com.finance.analytics.repository.TransactionEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final TransactionEntryRepository repository;

    // Color palettes for charts
    private final List<String> PIE_CHART_COLORS = Arrays.asList(
            "#FF6384", "#36A2EB", "#FFCE56", "#4BC0C0", "#9966FF",
            "#FF9F40", "#FF6384", "#C9CBCF", "#4BC0C0", "#FF6384"
    );

    private final List<String> LINE_CHART_COLORS = Arrays.asList(
            "#36A2EB", "#FF6384", "#4BC0C0"
    );

    @Autowired
    public AnalyticsService(TransactionEntryRepository repository) {
        this.repository = repository;
    }

    public ChartData getCategoryAnalytics(AnalyticsRequest request) {
        List<Object[]> results = getCategoryAnalyticsResults(request);

        List<CategoryAnalytics> categoryData = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        // Process results
        for (Object[] result : results) {
            String category = result[0] != null ? result[0].toString() : "Unknown";
            BigDecimal amount = (BigDecimal) result[1];
            Long count = ((Number) result[2]).longValue(); // Handle potential Integer/Long conversion

            categoryData.add(new CategoryAnalytics(category, amount, count));
            totalAmount = totalAmount.add(amount);
        }

        // Calculate percentages
        for (CategoryAnalytics data : categoryData) {
            if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
                double percentage = data.getTotalAmount()
                        .divide(totalAmount, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                data.setPercentage(percentage);
            }
        }

        // Create chart data
        List<String> labels = categoryData.stream()
                .map(CategoryAnalytics::getCategory)
                .collect(Collectors.toList());

        List<Double> amounts = categoryData.stream()
                .map(data -> data.getTotalAmount().doubleValue())
                .collect(Collectors.toList());

        String title = "Category Distribution";
        if (request.getTransactionFilter() != null) {
            title = request.getTransactionFilter().toString() + " Category Distribution";
        }

        ChartData.DataSet dataset = new ChartData.DataSet(
                "Amount", amounts, PIE_CHART_COLORS.subList(0, Math.min(labels.size(), PIE_CHART_COLORS.size()))
        );

        return new ChartData("pie", title, labels, List.of(dataset));
    }

    private List<Object[]> getCategoryAnalyticsResults(AnalyticsRequest request) {
        UUID userId = request.getUserId();
        String transactionType = request.getTransactionFilter() != null ?
                request.getTransactionFilter().name() : null;
        LocalDateTime startDate = request.getStartDate();
        LocalDateTime endDate = request.getEndDate();

        // Use appropriate repository method based on parameters
        if (transactionType != null && startDate != null && endDate != null) {
            return repository.getCategoryAnalyticsByTypeAndDateRange(userId, transactionType, startDate, endDate);
        } else if (transactionType != null) {
            return repository.getCategoryAnalyticsByType(userId, transactionType);
        } else if (startDate != null && endDate != null) {
            return repository.getCategoryAnalyticsByDateRange(userId, startDate, endDate);
        } else {
            return repository.getAllCategoryAnalytics(userId);
        }
    }

    public ChartData getTimelineAnalytics(AnalyticsRequest request) {
        String timelineType = request.getTimelineType() != null ?
                request.getTimelineType().toUpperCase() : "MONTHLY";

        List<TimelineAnalytics> timelineData;
        String chartTitle = switch (timelineType) {
            case "DAILY" -> {
                timelineData = getDailyTimelineData(request);
                yield "Daily Transaction Trends";
            }
            case "YEARLY" -> {
                timelineData = getYearlyTimelineData(request);
                yield "Yearly Transaction Trends";
            }
            default -> {
                timelineData = getMonthlyTimelineData(request);
                yield "Monthly Transaction Trends";
            }
        };

        return createTimelineChart(timelineData, chartTitle, request.getTransactionFilter());
    }

    private List<TimelineAnalytics> getDailyTimelineData(AnalyticsRequest request) {
        List<Object[]> results;
        if (request.getStartDate() != null && request.getEndDate() != null) {
            results = repository.getDailyAnalyticsByDateRange(
                    request.getUserId(), request.getStartDate(), request.getEndDate());
        } else {
            results = repository.getAllDailyAnalytics(request.getUserId());
        }

        return results.stream()
                .map(result -> {
                    LocalDate date = ((java.sql.Date) result[0]).toLocalDate();
                    BigDecimal income = (BigDecimal) result[1];
                    BigDecimal expense = (BigDecimal) result[2];
                    long count = ((Number) result[3]).longValue();

                    TimelineAnalytics analytics = new TimelineAnalytics(
                            date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            date.atStartOfDay(),
                            date.atTime(23, 59, 59)
                    );
                    analytics.setIncomeAmount(income != null ? income : BigDecimal.ZERO);
                    analytics.setExpenseAmount(expense != null ? expense : BigDecimal.ZERO);
                    analytics.setTotalTransactions(count);
                    return analytics;
                })
                .collect(Collectors.toList());
    }

    private List<TimelineAnalytics> getMonthlyTimelineData(AnalyticsRequest request) {
        List<Object[]> results;
        if (request.getStartDate() != null && request.getEndDate() != null) {
            results = repository.getMonthlyAnalyticsByDateRange(
                    request.getUserId(), request.getStartDate(), request.getEndDate());
        } else {
            results = repository.getAllMonthlyAnalytics(request.getUserId());
        }

        return results.stream()
                .map(result -> {
                    // Handle potential Double/Integer conversion from EXTRACT function
                    int year = ((Number) result[0]).intValue();
                    int month = ((Number) result[1]).intValue();
                    BigDecimal income = (BigDecimal) result[2];
                    BigDecimal expense = (BigDecimal) result[3];
                    Long count = ((Number) result[4]).longValue();

                    String timePeriod = String.format("%d-%02d", year, month);
                    LocalDateTime periodStart = LocalDateTime.of(year, month, 1, 0, 0);
                    LocalDateTime periodEnd = periodStart.plusMonths(1).minusSeconds(1);

                    return getTimelineAnalytics(income, expense, count, timePeriod, periodStart, periodEnd);
                })
                .collect(Collectors.toList());
    }

    private TimelineAnalytics getTimelineAnalytics(BigDecimal income, BigDecimal expense, Long count, String timePeriod, LocalDateTime periodStart, LocalDateTime periodEnd) {
        TimelineAnalytics analytics = new TimelineAnalytics(timePeriod, periodStart, periodEnd);
        analytics.setIncomeAmount(income != null ? income : BigDecimal.ZERO);
        analytics.setExpenseAmount(expense != null ? expense : BigDecimal.ZERO);
        analytics.setTotalTransactions(count != null ? count : 0);
        return analytics;
    }

    private List<TimelineAnalytics> getYearlyTimelineData(AnalyticsRequest request) {
        List<Object[]> results;
        if (request.getStartDate() != null && request.getEndDate() != null) {
            results = repository.getYearlyAnalyticsByDateRange(
                    request.getUserId(), request.getStartDate(), request.getEndDate());
        } else {
            results = repository.getAllYearlyAnalytics(request.getUserId());
        }

        return results.stream()
                .map(result -> {
                    int year = ((Number) result[0]).intValue();
                    BigDecimal income = (BigDecimal) result[1];
                    BigDecimal expense = (BigDecimal) result[2];
                    Long count = ((Number) result[3]).longValue();

                    String timePeriod = Integer.toString(year);
                    LocalDateTime periodStart = LocalDateTime.of(year, 1, 1, 0, 0);
                    LocalDateTime periodEnd = LocalDateTime.of(year, 12, 31, 23, 59, 59);

                    return getTimelineAnalytics(income, expense, count, timePeriod, periodStart, periodEnd);
                }).collect(Collectors.toList());
    }

    private ChartData createTimelineChart(List<TimelineAnalytics> timelineData, String title, TransactionType filter) {
        List<String> labels = timelineData.stream()
                .map(TimelineAnalytics::getTimePeriod)
                .collect(Collectors.toList());

        List<ChartData.DataSet> datasets = new ArrayList<>();

        if (filter == null || filter == TransactionType.INCOME) {
            List<Double> incomeData = timelineData.stream()
                    .map(data -> data.getIncomeAmount().doubleValue())
                    .collect(Collectors.toList());

            datasets.add(new ChartData.DataSet("Income", incomeData, List.of("#36A2EB")));
        }

        if (filter == null || filter == TransactionType.EXPENSE) {
            List<Double> expenseData = timelineData.stream()
                    .map(data -> data.getExpenseAmount().doubleValue())
                    .collect(Collectors.toList());

            datasets.add(new ChartData.DataSet("Expense", expenseData, List.of("#FF6384")));
        }

        if (filter == null) {
            List<Double> netData = timelineData.stream()
                    .map(data -> data.getNetAmount().doubleValue())
                    .collect(Collectors.toList());

            datasets.add(new ChartData.DataSet("Net Amount", netData, List.of("#4BC0C0")));
        }

        return new ChartData("line", title, labels, datasets);
    }

    public Map<String, Object> getComprehensiveAnalytics(AnalyticsRequest request) {
        Map<String, Object> analytics = new HashMap<>();

        // Get summary statistics
        BigDecimal totalIncome = getTotalAmountByType(request, TransactionType.INCOME);
        BigDecimal totalExpense = getTotalAmountByType(request, TransactionType.EXPENSE);

        analytics.put("totalIncome", totalIncome != null ? totalIncome : BigDecimal.ZERO);
        analytics.put("totalExpense", totalExpense != null ? totalExpense : BigDecimal.ZERO);
        analytics.put("netAmount",
                (totalIncome != null ? totalIncome : BigDecimal.ZERO)
                        .subtract(totalExpense != null ? totalExpense : BigDecimal.ZERO)
        );

        // Get category charts
        AnalyticsRequest incomeRequest = new AnalyticsRequest();
        incomeRequest.setUserId(request.getUserId());
        incomeRequest.setTransactionFilter(TransactionType.INCOME);
        incomeRequest.setStartDate(request.getStartDate());
        incomeRequest.setEndDate(request.getEndDate());

        AnalyticsRequest expenseRequest = new AnalyticsRequest();
        expenseRequest.setUserId(request.getUserId());
        expenseRequest.setTransactionFilter(TransactionType.EXPENSE);
        expenseRequest.setStartDate(request.getStartDate());
        expenseRequest.setEndDate(request.getEndDate());

        analytics.put("incomeByCategory", getCategoryAnalytics(incomeRequest));
        analytics.put("expenseByCategory", getCategoryAnalytics(expenseRequest));

        // Get timeline chart
        AnalyticsRequest timelineRequest = new AnalyticsRequest();
        timelineRequest.setUserId(request.getUserId());
        timelineRequest.setStartDate(request.getStartDate());
        timelineRequest.setEndDate(request.getEndDate());
        timelineRequest.setTimelineType(request.getTimelineType());

        analytics.put("timelineTrends", getTimelineAnalytics(timelineRequest));

        return analytics;
    }

    private BigDecimal getTotalAmountByType(AnalyticsRequest request, TransactionType type) {
        UUID userId = request.getUserId();
        String typeString = type.name();
        LocalDateTime startDate = request.getStartDate();
        LocalDateTime endDate = request.getEndDate();

        if (startDate != null && endDate != null) {
            return repository.getTotalAmountByTypeAndDateRange(userId, typeString, startDate, endDate);
        } else {
            return repository.getTotalAmountByType(userId, typeString);
        }
    }
    public Page<TransactionEntry> getTransactionEntriesByUserId(UUID userId, Pageable pageable) {
        return repository.findByUserId(userId, pageable);
    }
    public Page<TransactionEntry> findIncomeByCategoryAndDate(
            UUID userId,
            IncomeCategory incomeCategory,
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable) {

        return repository.findByUserIdAndIncomeCategoryAndCreatedAtBetween(
                userId,
                incomeCategory,
                start,
                end,
                pageable
        );
    }
    public Page<TransactionEntry> findTransactionsByTypeAndDate(
            UUID userId,
            TransactionType type,
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable) {

        return repository.findByUserIdAndTypeAndCreatedAtBetween(
                userId,
                type,
                start,
                end,
                pageable
        );
    }
}