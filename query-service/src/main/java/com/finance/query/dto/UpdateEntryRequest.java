package com.finance.query.dto;

import com.finance.query.model.Category;
import com.finance.query.model.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

import com.finance.query.model.RecurringPeriod;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateEntryRequest {

    @NotNull(message = "Transaction ID is required")
    private Long id;

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

    private boolean recurring = false;

    private RecurringPeriod recurringPeriod;

    private java.time.LocalDateTime createdAt;

    public UpdateEntryRequest() {}
}

