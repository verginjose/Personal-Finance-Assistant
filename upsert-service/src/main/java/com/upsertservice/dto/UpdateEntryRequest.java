package com.upsertservice.dto;

import com.upsertservice.model.ExpenseCategory;
import com.upsertservice.model.IncomeCategory;
import com.upsertservice.model.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

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

    private ExpenseCategory expenseCategory;

    private IncomeCategory incomeCategory;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    private String currency;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    // Constructors
    public UpdateEntryRequest() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public ExpenseCategory getExpenseCategory() { return expenseCategory; }
    public void setExpenseCategory(ExpenseCategory expenseCategory) { this.expenseCategory = expenseCategory; }

    public IncomeCategory getIncomeCategory() { return incomeCategory; }
    public void setIncomeCategory(IncomeCategory incomeCategory) { this.incomeCategory = incomeCategory; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

