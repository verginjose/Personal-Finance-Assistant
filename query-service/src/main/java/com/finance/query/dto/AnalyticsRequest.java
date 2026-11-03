package com.finance.query.dto;

import com.finance.query.model.TransactionType;
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

    /**
     * The browser's offset from UTC in minutes, east-positive (IST = 330).
     * {@code startDate}/{@code endDate} arrive as the user's *local* wall-clock
     * values; timestamps are stored in UTC, so the offset is needed both to
     * filter the right instants and to group days/months/weeks the way the
     * user's calendar sees them. Null means UTC (and keeps older clients,
     * which never sent the parameter, working).
     */
    private Integer tzOffsetMinutes;

    public int resolvedTzOffset() {
        return tzOffsetMinutes != null ? tzOffsetMinutes : 0;
    }

    public LocalDateTime startUtc() {
        return startDate != null ? startDate.minusMinutes(resolvedTzOffset()) : null;
    }

    public LocalDateTime endUtc() {
        return endDate != null ? endDate.minusMinutes(resolvedTzOffset()) : null;
    }

    /** Normalized cache-safe date string (date only, no time component). */
    public String cacheStartDate() {
        return startDate != null ? startDate.toLocalDate().toString() : "null";
    }

    public String cacheEndDate() {
        return endDate != null ? endDate.toLocalDate().toString() : "null";
    }

    /** Cache-safe timezone token. */
    public String cacheTimezone() {
        return String.valueOf(resolvedTzOffset());
    }
}