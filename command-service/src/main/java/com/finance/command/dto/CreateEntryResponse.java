package com.finance.command.dto;

import com.finance.command.model.Category;
import com.finance.command.model.TransactionType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
public class CreateEntryResponse {

    private com.finance.command.model.TransactionKind transactionKind;
    private Long originalTransactionId;
    private String subcategory;

    private Long id;
    private UUID userId;
    private String name;
    private BigDecimal amount;
    private TransactionType type;
    private Category category;
    private String currency;
    private String description;
    private boolean recurring;
    private com.finance.command.model.RecurringPeriod recurringPeriod;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String receiptUrl;

    public CreateEntryResponse(
            Long id, UUID userId, String name,
            BigDecimal amount, TransactionType type, Category category,
            String currency, String description, boolean recurring,
            com.finance.command.model.RecurringPeriod recurringPeriod, LocalDateTime createdAt, LocalDateTime updatedAt,
            String receiptUrl) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.amount = amount;
        this.type = type;
        this.category = category;
        this.currency = currency;
        this.description = description;
        this.recurring = recurring;
        this.recurringPeriod = recurringPeriod;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.receiptUrl = receiptUrl;
    }
}
