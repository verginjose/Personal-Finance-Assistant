package com.upsertservice.dto;

import com.upsertservice.model.ExpenseCategory;
import com.upsertservice.model.RecurringPeriod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor
public class BudgetUtilizationResponse {
    private Long budgetId;
    private ExpenseCategory expenseCategory;
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
