package com.upsertservice.dto;

import com.upsertservice.model.ExpenseCategory;
import com.upsertservice.model.RecurringPeriod;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data @NoArgsConstructor
public class CategoryBudgetRequest {
    @NotNull  private UUID userId;
    @NotNull  private ExpenseCategory expenseCategory;
    @NotNull @DecimalMin("0.01") private BigDecimal budgetAmount;
    @NotNull  private RecurringPeriod period;
    @NotBlank @Size(min=3,max=3) private String currency;
}
