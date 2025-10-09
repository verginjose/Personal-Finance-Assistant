package com.finance.analytics.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class TimelineAnalytics {
    private final String timePeriod; // "2024-01", "2024-W01", "2024-01-01"
    private final LocalDateTime periodStart;
    private final LocalDateTime periodEnd;
    private BigDecimal incomeAmount;
    private BigDecimal expenseAmount;
    private BigDecimal netAmount;
    @Setter
    private long totalTransactions;

    public TimelineAnalytics(String timePeriod, LocalDateTime periodStart, LocalDateTime periodEnd) {
        this.timePeriod = timePeriod;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.incomeAmount = BigDecimal.ZERO;
        this.expenseAmount = BigDecimal.ZERO;
        this.netAmount = BigDecimal.ZERO;
    }

    public void setIncomeAmount(BigDecimal incomeAmount) {
        this.incomeAmount = incomeAmount;
        calculateNetAmount();
    }

    public void setExpenseAmount(BigDecimal expenseAmount) {
        this.expenseAmount = expenseAmount;
        calculateNetAmount();
    }

    private void calculateNetAmount() {
        this.netAmount = (incomeAmount != null ? incomeAmount : BigDecimal.ZERO)
                .subtract(expenseAmount != null ? expenseAmount : BigDecimal.ZERO);
    }
}
