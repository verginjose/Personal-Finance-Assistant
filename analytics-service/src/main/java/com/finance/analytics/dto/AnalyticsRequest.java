package com.finance.analytics.dto;

import com.finance.analytics.model.TransactionType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class AnalyticsRequest {

    // Getters and Setters
    @NotNull(message = "User ID is required")
    private UUID userId;

    private TransactionType transactionFilter; // INCOME, EXPENSE, or null for both

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    private String timelineType; // "DAILY", "WEEKLY", "MONTHLY", "YEARLY"

    // Constructors
    public AnalyticsRequest() {}

}

