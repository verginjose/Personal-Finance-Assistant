package com.upsertservice.model;

import com.upsertservice.model.RecurringPeriod;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transaction_entries", schema = "finance", indexes = {
    @Index(name = "idx_transaction_user_deleted", columnList = "user_id, deleted_at"),
    @Index(name = "idx_transaction_user_type_created", columnList = "user_id, type, created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @NotNull(message = "User ID is required")
    private UUID userId;

    @Column(nullable = false, length = 100)
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    private String name;

    @Column(nullable = false)
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull(message = "Transaction type is required")
    private TransactionType type;

    @Column(name = "expense_category")
    @Enumerated(EnumType.STRING)
    private ExpenseCategory expenseCategory;

    @Column(name = "income_category")
    @Enumerated(EnumType.STRING)
    private IncomeCategory incomeCategory;

    @Column(nullable = false, length = 3)
    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    private String currency;

    @Column(length = 500)
    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Whether this is a recurring transaction. Default false. */
    @Column(name = "recurring", nullable = false)
    private boolean recurring = false;

    /**
     * Recurrence period — only meaningful when {@code recurring = true}.
     * Hibernate adds this as a nullable VARCHAR column automatically.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recurring_period")
    private RecurringPeriod recurringPeriod;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        validateCategoryConsistency();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        validateCategoryConsistency();
    }

    private void validateCategoryConsistency() {
        if (type == TransactionType.EXPENSE && expenseCategory == null) {
            throw new IllegalStateException("Expense category is required for expense transactions");
        }
        if (type == TransactionType.INCOME && incomeCategory == null) {
            throw new IllegalStateException("Income category is required for income transactions");
        }
        if (type == TransactionType.EXPENSE && incomeCategory != null) {
            throw new IllegalStateException("Income category should not be set for expense transactions");
        }
        if (type == TransactionType.INCOME && expenseCategory != null) {
            throw new IllegalStateException("Expense category should not be set for income transactions");
        }
    }

    public TransactionEntry(UUID userId, String name, BigDecimal amount,
                            TransactionType type, String currency) {
        this.userId = userId;
        this.name = name;
        this.amount = amount;
        this.type = type;
        this.currency = currency;
    }
}
