package com.finance.analytics.dto;


import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
public class CategoryAnalytics {
    // Getters and Setters
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

}
