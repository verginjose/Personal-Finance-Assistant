package com.finance.analytics.controller;

import com.finance.analytics.dto.AnalyticsRequest;
import com.finance.analytics.dto.ChartData;
import com.finance.analytics.model.Category;
import com.finance.analytics.model.TransactionEntry;
import com.finance.analytics.model.TransactionType;
import com.finance.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@Tag(name = "Transaction Analytics", description = "APIs for transaction analytics and charts")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final com.finance.analytics.service.GoalForecastingService forecastingService;
    private final com.finance.analytics.service.BudgetTrendService trendService;

    @GetMapping("/category-pie-chart")
    @Operation(summary = "Get category distribution pie chart")
    public ResponseEntity<ChartData> getCategoryPieChart(
            @Parameter(description = "User ID", required = true) @RequestParam UUID userId,
            @Parameter(description = "Transaction filter") @RequestParam(required = false) TransactionType transactionFilter,
            @Parameter(description = "Start date") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        AnalyticsRequest request = new AnalyticsRequest();
        request.setUserId(userId);
        request.setTransactionFilter(transactionFilter);
        request.setStartDate(startDate);
        request.setEndDate(endDate);

        return ResponseEntity.ok(analyticsService.getCategoryAnalytics(request));
    }

    @GetMapping("/timeline-chart")
    @Operation(summary = "Get timeline chart")
    public ResponseEntity<ChartData> getTimelineChart(
            @Parameter(description = "User ID", required = true) @RequestParam UUID userId,
            @Parameter(description = "Timeline type") @RequestParam(defaultValue = "MONTHLY") String timelineType,
            @Parameter(description = "Transaction filter") @RequestParam(required = false) TransactionType transactionFilter,
            @Parameter(description = "Start date") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        AnalyticsRequest request = new AnalyticsRequest();
        request.setUserId(userId);
        request.setTransactionFilter(transactionFilter);
        request.setTimelineType(timelineType);
        request.setStartDate(startDate);
        request.setEndDate(endDate);

        return ResponseEntity.ok(analyticsService.getTimelineAnalytics(request));
    }

    @GetMapping("/comprehensive")
    @Operation(summary = "Get comprehensive analytics")
    public ResponseEntity<Map<String, Object>> getComprehensiveAnalytics(
            @Parameter(description = "User ID", required = true) @RequestParam UUID userId,
            @Parameter(description = "Timeline type") @RequestParam(defaultValue = "MONTHLY") String timelineType,
            @Parameter(description = "Start date") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        AnalyticsRequest request = new AnalyticsRequest();
        request.setUserId(userId);
        request.setTimelineType(timelineType);
        request.setStartDate(startDate);
        request.setEndDate(endDate);

        return ResponseEntity.ok(analyticsService.getComprehensiveAnalytics(request));
    }

    @PostMapping("/custom-analytics")
    @Operation(summary = "Get custom analytics with POST body")
    public ResponseEntity<Map<String, Object>> getCustomAnalytics(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody AnalyticsRequest request) {
        if (request.getUserId() == null || !request.getUserId().toString().equalsIgnoreCase(xUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: User ID mismatch");
        }
        return ResponseEntity.ok(analyticsService.getComprehensiveAnalytics(request));
    }

    @GetMapping("/transaction-entries")
    public ResponseEntity<Page<TransactionEntry>> getTransactionEntries(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(analyticsService.getTransactionEntriesByUserId(userId, pageable));
    }

    @GetMapping("/transactions/income-by-category")
    public ResponseEntity<Page<TransactionEntry>> getIncomeTransactions(
            @RequestParam UUID userId,
            @RequestParam Category incomeCategory,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        LocalDateTime endDateTime = (endDate == null) ? LocalDateTime.now() : endDate.atTime(LocalTime.MAX);
        LocalDateTime startDateTime = (startDate == null) ? endDateTime.minusYears(1) : startDate.atStartOfDay();
        Pageable pageable = PageRequest.of(page, size);

        return ResponseEntity.ok(analyticsService.findIncomeByCategoryAndDate(
                userId, incomeCategory, startDateTime, endDateTime, pageable));
    }

    @GetMapping("/transactions/by-type")
    public ResponseEntity<Page<TransactionEntry>> getTransactionsByType(
            @RequestParam UUID userId,
            @RequestParam TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        LocalDateTime endDateTime = (endDate == null) ? LocalDateTime.now() : endDate.atTime(LocalTime.MAX);
        LocalDateTime startDateTime = (startDate == null) ? endDateTime.minusYears(1) : startDate.atStartOfDay();
        Pageable pageable = PageRequest.of(page, size);

        return ResponseEntity.ok(analyticsService.findTransactionsByTypeAndDate(
                userId, type, startDateTime, endDateTime, pageable));
    }

    @GetMapping("/goals/{id}/forecast")
    @Operation(summary = "Get goal completion forecast")
    public ResponseEntity<com.finance.analytics.service.GoalForecastingService.GoalForecast> getGoalForecast(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") String xUserId) {
        return ResponseEntity.ok(forecastingService.forecastGoal(id, UUID.fromString(xUserId)));
    }

    @GetMapping("/budgets/trends")
    @Operation(summary = "Get month-over-month budget trends")
    public ResponseEntity<java.util.List<com.finance.analytics.service.BudgetTrendService.BudgetTrend>> getBudgetTrends(
            @RequestHeader("X-User-Id") String xUserId) {
        return ResponseEntity.ok(trendService.getTrends(UUID.fromString(xUserId)));
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "analytics-service"));
    }
}
