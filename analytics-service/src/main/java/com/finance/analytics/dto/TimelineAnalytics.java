package com.finance.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TimelineAnalytics {
    private String timePeriod; // "2024-01", "2024-W01", "2024-01-01"
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private BigDecimal incomeAmount;
    private BigDecimal expenseAmount;
    private BigDecimal netAmount;
    private long totalTransactions;

    // Constructors
    public TimelineAnalytics() {}

    public TimelineAnalytics(String timePeriod, LocalDateTime periodStart, LocalDateTime periodEnd) {
        this.timePeriod = timePeriod;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.incomeAmount = BigDecimal.ZERO;
        this.expenseAmount = BigDecimal.ZERO;
        this.netAmount = BigDecimal.ZERO;
    }

    // Getters and Setters
    public String getTimePeriod() { return timePeriod; }
    public void setTimePeriod(String timePeriod) { this.timePeriod = timePeriod; }

    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }

    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }

    public BigDecimal getIncomeAmount() { return incomeAmount; }
    public void setIncomeAmount(BigDecimal incomeAmount) {
        this.incomeAmount = incomeAmount;
        calculateNetAmount();
    }

    public BigDecimal getExpenseAmount() { return expenseAmount; }
    public void setExpenseAmount(BigDecimal expenseAmount) {
        this.expenseAmount = expenseAmount;
        calculateNetAmount();
    }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public long getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(long totalTransactions) { this.totalTransactions = totalTransactions; }

    private void calculateNetAmount() {
        this.netAmount = (incomeAmount != null ? incomeAmount : BigDecimal.ZERO)
                .subtract(expenseAmount != null ? expenseAmount : BigDecimal.ZERO);
    }
}
