package com.finance.query.dto;

import com.finance.query.model.Category;
import com.finance.query.model.TransactionType;
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

    private com.finance.query.model.TransactionKind transactionKind;
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
    private com.finance.query.model.RecurringPeriod recurringPeriod;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String receiptUrl;
    /** The amount in the user's default currency; null when not converted. */
    private BigDecimal amountDefault;

    public CreateEntryResponse(
            Long id, UUID userId, String name,
            BigDecimal amount, TransactionType type, Category category,
            String currency, String description, boolean recurring,
            com.finance.query.model.RecurringPeriod recurringPeriod, LocalDateTime createdAt, LocalDateTime updatedAt,
            String receiptUrl, BigDecimal amountDefault) {
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
        this.amountDefault = amountDefault;
    }
}
