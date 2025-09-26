package com.finance.analytics.dto;


import java.math.BigDecimal;

public class CategoryAnalytics {
    private String category;
    private BigDecimal totalAmount;
    private long transactionCount;
    private double percentage;

    // Constructors
    public CategoryAnalytics() {}

    public CategoryAnalytics(String category, BigDecimal totalAmount, long transactionCount) {
        this.category = category;
        this.totalAmount = totalAmount;
        this.transactionCount = transactionCount;
    }

    // Getters and Setters
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }

    public double getPercentage() { return percentage; }
    public void setPercentage(double percentage) { this.percentage = percentage; }
}
