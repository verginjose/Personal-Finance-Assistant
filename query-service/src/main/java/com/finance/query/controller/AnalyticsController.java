package com.finance.query.controller;

import com.finance.query.dto.AnalyticsRequest;
import com.finance.query.dto.ChartData;
import com.finance.query.model.Category;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.service.AnalyticsService;
import com.finance.query.service.BudgetTrendService;
import com.finance.query.service.GoalForecastingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final GoalForecastingService goalForecastingService;
    private final BudgetTrendService budgetTrendService;

    @GetMapping("/category-pie-chart")
    public ChartData getCategoryPieChart(@ModelAttribute AnalyticsRequest request) {
        return analyticsService.getCategoryAnalytics(request);
    }

    @GetMapping("/timeline-chart")
    public ChartData getTimelineChart(@ModelAttribute AnalyticsRequest request) {
        return analyticsService.getTimelineAnalytics(request);
    }

    @GetMapping("/comprehensive")
    public Map<String, Object> getComprehensiveAnalytics(@ModelAttribute AnalyticsRequest request) {
        return analyticsService.getComprehensiveAnalytics(request);
    }

    @GetMapping("/transaction-entries")
    public Page<TransactionEntry> getTransactionEntries(@RequestParam UUID userId, Pageable pageable) {
        return analyticsService.getTransactionEntriesByUserId(userId, pageable);
    }


    @GetMapping("/transactions/income-by-category")
    public Page<TransactionEntry> getIncomeByCategory(
            @RequestParam UUID userId,
            @RequestParam Category categoryName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Pageable pageable) {
        return analyticsService.findIncomeByCategoryAndDate(userId, categoryName, startDate.atStartOfDay(), endDate.atTime(23, 59, 59), pageable);
    }

    @GetMapping("/transactions/by-type")
    public Page<TransactionEntry> getTransactionsByType(
            @RequestParam UUID userId,
            @RequestParam TransactionType transactionType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Pageable pageable) {
        return analyticsService.findTransactionsByTypeAndDate(userId, transactionType, startDate.atStartOfDay(), endDate.atTime(23, 59, 59), pageable);
    }

    @GetMapping("/goals/{id}/forecast")
    public Object getGoalForecast(@PathVariable Long id, @RequestParam UUID userId) {
        return goalForecastingService.forecastGoal(id, userId);
    }

    @GetMapping("/budgets/trends")
    public Object getBudgetTrends(@RequestParam UUID userId) {
        return budgetTrendService.getTrends(userId);
    }
}
