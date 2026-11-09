package com.finance.query.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_entries", schema = "finance", indexes = {
        @Index(name = "idx_transaction_user_deleted", columnList = "user_id, deleted_at"),
        @Index(name = "idx_transaction_user_type_created", columnList = "user_id, type, created_at")
})
public class TransactionEntry implements Serializable {
    private static final long serialVersionUID = 1L;

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

    @Column(name = "category", nullable = false)
    @Enumerated(EnumType.STRING)
    @Setter
    @Getter
    private Category category;


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

    @Column(name = "created_at", nullable = false)
    @Getter
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @Getter
    @Setter
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Whether this is a recurring transaction. Default false. */
    @Getter
    @Column(name = "recurring", nullable = false)
    @Setter
    private boolean recurring = false;

    /**
     * Recurrence period — only meaningful when {@code recurring = true}.
     * Hibernate adds this as a nullable VARCHAR column automatically.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recurring_period", length = 20)
    @Getter
    @Setter
    private RecurringPeriod recurringPeriod;

    @Column(name = "next_run_date")
    @Getter
    @Setter
    private LocalDateTime nextRunDate;

    @Setter
    @Getter
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** MinIO receipt/bill image URL */
    @Column(name = "receipt_url", length = 500)
    @Getter
    @Setter
    private String receiptUrl;

    /*
     * Group context for rows that came from a shared expense. Resolved per
     * request from expense_transaction_links and never persisted here.
     */
    @Transient
    @Getter
    @Setter
    private Long groupId;

    @Transient
    @Getter
    @Setter
    private String groupName;

    @Transient
    @Getter
    @Setter
    private BigDecimal totalAmount;

    @Transient
    @Getter
    @Setter
    private BigDecimal myShare;

    /*
     * The amount in the user's default currency, resolved per request from the
     * cached FX rates and never persisted. Null means "not converted" — either
     * the row is already in the default currency's peer set or no rate was
     * available — so the client shows the original amount.
     */
    @Transient
    @Getter
    @Setter
    private BigDecimal amountDefault;
    @Getter @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_kind", length = 20)
    private TransactionKind transactionKind = TransactionKind.STANDARD;

    @Getter @Setter
    @Column(name = "original_transaction_id")
    private Long originalTransactionId;

    @Getter @Setter
    @Size(max = 80)
    @Column(name = "subcategory", length = 80)
    private String subcategory;

    // Formula fields let JPQL use precisely the same policy as native timelines.
    @org.hibernate.annotations.Formula(AccountingRules.REPORTABLE_SQL)
    private boolean reportable;

    @org.hibernate.annotations.Formula(AccountingRules.AMOUNT_SQL)
    private BigDecimal reportingAmount;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.Formula(AccountingRules.TYPE_SQL)
    private TransactionType reportingType;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isReportable() { return AccountingRules.isReportable(this); }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public BigDecimal getReportingAmount() { return AccountingRules.amount(this); }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public TransactionType getReportingType() { return AccountingRules.type(this); }

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