package com.upsertservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shared_expenses", schema = "groups", indexes = {
    @Index(name = "idx_shared_expense_group", columnList = "group_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharedExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String description;

    @NotNull
    @DecimalMin("0.01")
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "paid_by", nullable = false)
    private UUID paidBy;   // userId of who paid

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false)
    private SplitType splitType = SplitType.EQUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_category")
    private ExpenseCategory expenseCategory;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum SplitType {
        EQUAL, PERCENTAGE, EXACT
    }

    public enum ExpenseCategory {
        FOOD_AND_DINING, TRANSPORTATION, SHOPPING, ENTERTAINMENT,
        BILLS_AND_UTILITIES, HEALTHCARE, TRAVEL, EDUCATION, OTHERS
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
