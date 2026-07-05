package com.finance.query.dto;

import com.finance.query.model.Category;
import com.finance.query.model.RecurringPeriod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor
public class BudgetUtilizationResponse {
    private Long budgetId;
    private Category expenseCategory;
    private BigDecimal budgetAmount;
    private BigDecimal spentAmount;
    private double utilizationPercentage;
    private RecurringPeriod period;
    private String currency;
    private String status; // SAFE / WARNING / EXCEEDED

    public static String deriveStatus(double pct) {
        if (pct >= 100) return "EXCEEDED";
        if (pct >= 80)  return "WARNING";
        return "SAFE";
    }
}
