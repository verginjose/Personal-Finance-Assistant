package com.finance.analytics.controller;

import com.finance.analytics.dto.AnalyticsRequest;
import com.finance.analytics.dto.ChartData;
import com.finance.analytics.model.TransactionType;
import com.finance.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/analytics")
@Tag(name = "Transaction Analytics", description = "APIs for transaction analytics and charts")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Autowired
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/category-pie-chart")
    @Operation(summary = "Get category distribution pie chart", description = "Returns pie chart data for transaction categories")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Chart data retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> getCategoryPieChart(
            @Parameter(description = "User ID", required = true) @RequestParam UUID userId,

            @Parameter(description = "Transaction filter (INCOME, EXPENSE, or both)") @RequestParam(required = false) TransactionType transactionFilter,

            @Parameter(description = "Start date for filtering (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,

            @Parameter(description = "End date for filtering (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        try {
            AnalyticsRequest request = new AnalyticsRequest();
            request.setUserId(userId);
            request.setTransactionFilter(transactionFilter);
            request.setStartDate(startDate);
            request.setEndDate(endDate);

            ChartData chartData = analyticsService.getCategoryAnalytics(request);
            return ResponseEntity.ok(chartData);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to generate category chart",
                            "message", e.getMessage(),
                            "timestamp", LocalDateTime.now()));
        }
    }

    @GetMapping("/timeline-chart")
    @Operation(summary = "Get timeline chart", description = "Returns line/bar chart data for transaction trends over time")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Chart data retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> getTimelineChart(
            @Parameter(description = "User ID", required = true) @RequestParam UUID userId,

            @Parameter(description = "Timeline type (DAILY, MONTHLY, YEARLY)") @RequestParam(defaultValue = "MONTHLY") String timelineType,

            @Parameter(description = "Transaction filter (INCOME, EXPENSE, or both)") @RequestParam(required = false) TransactionType transactionFilter,

            @Parameter(description = "Start date for filtering (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,

            @Parameter(description = "End date for filtering (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        try {
            AnalyticsRequest request = new AnalyticsRequest();
            request.setUserId(userId);
            request.setTransactionFilter(transactionFilter);
            request.setTimelineType(timelineType);
            request.setStartDate(startDate);
            request.setEndDate(endDate);

            ChartData chartData = analyticsService.getTimelineAnalytics(request);
            return ResponseEntity.ok(chartData);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to generate timeline chart",
                            "message", e.getMessage(),
                            "timestamp", LocalDateTime.now()));
        }
    }

    @GetMapping("/comprehensive")
    @Operation(summary = "Get comprehensive analytics", description = "Returns all analytics data including summaries and charts")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Analytics data retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> getComprehensiveAnalytics(
            @Parameter(description = "User ID", required = true) @RequestParam UUID userId,

            @Parameter(description = "Timeline type for trends (DAILY, MONTHLY, YEARLY)") @RequestParam(defaultValue = "MONTHLY") String timelineType,

            @Parameter(description = "Start date for filtering (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,

            @Parameter(description = "End date for filtering (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        try {
            AnalyticsRequest request = new AnalyticsRequest();
            request.setUserId(userId);
            request.setTimelineType(timelineType);
            request.setStartDate(startDate);
            request.setEndDate(endDate);

            Map<String, Object> analytics = analyticsService.getComprehensiveAnalytics(request);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to generate comprehensive analytics",
                            "message", e.getMessage(),
                            "timestamp", LocalDateTime.now()));
        }
    }

    @PostMapping("/custom-analytics")
    @Operation(summary = "Get custom analytics with POST body", description = "Returns custom analytics based on complex request parameters")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Analytics generated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> getCustomAnalytics(@Valid @RequestBody AnalyticsRequest request,
            BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            List<String> errors = bindingResult.getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.toList());

            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Validation failed",
                    "details", errors,
                    "timestamp", LocalDateTime.now()));
        }

        try {
            Map<String, Object> analytics = analyticsService.getComprehensiveAnalytics(request);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to generate custom analytics",
                            "message", e.getMessage(),
                            "timestamp", LocalDateTime.now()));
        }
    }
    @GetMapping("/transaction-entries")
    public ResponseEntity<?> getTransactionEntries(@RequestParam UUID userId,@RequestParam(defaultValue = "0") int page,@RequestParam(defaultValue = "10") int size) {
        try{
            Pageable pageable = PageRequest.of(page, size);
            var response=analyticsService.getTransactionEntriesByUserId(userId, pageable);
            return ResponseEntity.ok(response);
        }catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to get TransactionEntries",
                            "message", e.getMessage(),
                            "timestamp", LocalDateTime.now()));
        }
    }
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if the analytics service is running")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Analytics Service",
                "timestamp", LocalDateTime.now()));
    }

}
