package com.finance.command.dto;

import com.finance.command.model.SharedExpense;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class CreateSharedExpenseRequest {

    private Long groupId;

    @NotBlank
    @Size(max = 100)
    private String description;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    private String currency = "INR";

    @NotNull
    private UUID paidBy;

    /** EQUAL | PERCENTAGE | EXACT */
    private String splitType = "EQUAL";

    /** FOOD_AND_DINING | TRANSPORTATION | SHOPPING | etc. */
    private String expenseCategory;

    private LocalDateTime expenseDate;

    private List<SplitDetailRequest> splitDetails;

    @Data
    public static class SplitDetailRequest {
        @NotNull
        private UUID userId;
        private String userName;
        @DecimalMin("0")
        private BigDecimal value;
    }
}
