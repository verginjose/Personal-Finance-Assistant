package com.finance.analytics.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_entries")
public class TransactionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    @Setter
    private Long id;

    @Column(name = "user_id", nullable = false)
    @NotNull(message = "User ID is required")
    @Getter
    @Setter
    private UUID userId;

    @Column(nullable = false, length = 100)
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    @Getter
    @Setter
    private String name;

    @Column(nullable = false)
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    @Getter
    @Setter
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull(message = "Transaction type is required")
    @Getter
    @Setter
    private TransactionType type;

    @Column(name = "expense_category")
    @Enumerated(EnumType.STRING)
    @Getter
    @Setter
    private ExpenseCategory expenseCategory;

    @Column(name = "income_category")
    @Enumerated(EnumType.STRING)
    @Getter
    @Setter
    private IncomeCategory incomeCategory;

    @Column(nullable = false, length = 3)
    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    @Getter
    @Setter
    private String currency;

    @Column(length = 500)
    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Getter
    @Setter
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @Getter
    @Setter
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public TransactionEntry() {}

    public TransactionEntry(UUID userId, String name, BigDecimal amount,
                            TransactionType type, String currency) {
        this.userId = userId;
        this.name = name;
        this.amount = amount;
        this.type = type;
        this.currency = currency;
    }

}