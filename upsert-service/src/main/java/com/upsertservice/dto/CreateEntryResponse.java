package com.upsertservice.dto;

import com.upsertservice.model.ExpenseCategory;
import com.upsertservice.model.IncomeCategory;
import com.upsertservice.model.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class CreateEntryResponse {

    private Long id;
    private UUID userId;
    private String name;
    private BigDecimal amount;
    private TransactionType type;
    private ExpenseCategory expenseCategory;
    private IncomeCategory incomeCategory;
    private String currency;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructors
    public CreateEntryResponse() {}

    public CreateEntryResponse(Long id, UUID userId, String name, BigDecimal amount,
                               TransactionType type, ExpenseCategory expenseCategory,
                               IncomeCategory incomeCategory, String currency,
                               String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.amount = amount;
        this.type = type;
        this.expenseCategory = expenseCategory;
        this.incomeCategory = incomeCategory;
        this.currency = currency;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
