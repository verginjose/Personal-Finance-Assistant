package com.upsertservice.dto;

import com.upsertservice.model.Category;
import com.upsertservice.model.RecurringPeriod;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data @NoArgsConstructor
public class CategoryBudgetRequest {
    @NotNull  private UUID userId;
    @NotNull  private Category expenseCategory;
    @NotNull @DecimalMin("0.01") private BigDecimal budgetAmount;
    @NotNull  private RecurringPeriod period;
    @NotBlank @Size(min=3,max=3) private String currency;
    private boolean carryForward = false;
    private java.time.LocalDate customStartDate;
    private java.time.LocalDate customEndDate;
}
