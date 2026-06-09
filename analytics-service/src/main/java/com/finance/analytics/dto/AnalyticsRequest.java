package com.finance.analytics.dto;

import com.finance.analytics.model.TransactionType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsRequest {

    private UUID userId;
    private TransactionType transactionFilter;
    private String timelineType;
    private LocalDateTime startDate;
    private LocalDateTime endDate;

    /** Normalized cache-safe date string (date only, no time component). */
    public String cacheStartDate() {
        return startDate != null ? startDate.toLocalDate().toString() : "null";
    }

    public String cacheEndDate() {
        return endDate != null ? endDate.toLocalDate().toString() : "null";
    }
}