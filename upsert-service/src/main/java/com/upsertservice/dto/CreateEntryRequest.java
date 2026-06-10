package com.upsertservice.dto;

import com.upsertservice.model.Category;
import com.upsertservice.model.RecurringPeriod;
import com.upsertservice.model.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateEntryRequest {

    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    private String name;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    @NotNull(message = "Transaction type is required")
    private TransactionType type;

    private Category category;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    private String currency;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    /** Set to true for a recurring transaction. Defaults to false. */
    private boolean recurring = false;

    /** Required when recurring = true. */
    private RecurringPeriod recurringPeriod;

    /** Optional list of goal allocations when this transaction contributes to goals. */
    private java.util.List<GoalAllocationRequest> allocations;

    /** Optional creation date for retroactively adding transactions. */
    private java.time.LocalDateTime createdAt;
}
