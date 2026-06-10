package com.finance.analytics.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "category_budgets", schema = "finance", indexes = {
    @Index(name = "idx_budget_user", columnList = "user_id, active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @NotNull
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_category", nullable = false)
    @NotNull
    private Category expenseCategory;

    @Column(name = "budget_amount", nullable = false, precision = 12, scale = 2)
    @NotNull @DecimalMin("0.01")
    private BigDecimal budgetAmount;

    /** MONTHLY or WEEKLY period for budget reset. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull
    private RecurringPeriod period;

    @Column(nullable = false, length = 3)
    @NotBlank
    private String currency;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "carry_forward", nullable = false)
    private boolean carryForward = false;

    @Column(name = "custom_start_date")
    private java.time.LocalDate customStartDate;

    @Column(name = "custom_end_date")
    private java.time.LocalDate customEndDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
