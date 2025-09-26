package com.finance.analytics.dto;

import com.finance.analytics.model.TransactionType;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;

public class AnalyticsRequest {

    @NotNull(message = "User ID is required")
    private UUID userId;

    private TransactionType transactionFilter; // INCOME, EXPENSE, or null for both

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    private String timelineType; // "DAILY", "WEEKLY", "MONTHLY", "YEARLY"

    // Constructors
    public AnalyticsRequest() {}

    // Getters and Setters
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public TransactionType getTransactionFilter() { return transactionFilter; }
    public void setTransactionFilter(TransactionType transactionFilter) { this.transactionFilter = transactionFilter; }

    public LocalDateTime getStartDate() { return startDate; }
    public void setStartDate(LocalDateTime startDate) { this.startDate = startDate; }

    public LocalDateTime getEndDate() { return endDate; }
    public void setEndDate(LocalDateTime endDate) { this.endDate = endDate; }

    public String getTimelineType() { return timelineType; }
    public void setTimelineType(String timelineType) { this.timelineType = timelineType; }
}

