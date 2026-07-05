package com.finance.query.dto;

import com.finance.query.model.RecurringPeriod;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data @NoArgsConstructor
public class SavingsGoalRequest {
    @NotNull  private UUID userId;
    @NotBlank private String name;
    @NotNull @DecimalMin("0.01") private BigDecimal targetAmount;
    @NotBlank @Size(min=3,max=3) private String currency;
    private String description;
    private LocalDate deadline;
    private com.finance.query.model.Priority priority = com.finance.query.model.Priority.MEDIUM;
}
