package com.finance.query.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "savings_goals", schema = "finance", indexes = {
    @Index(name = "idx_goal_user", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @NotNull
    private UUID userId;

    @Column(nullable = false, length = 100)
    @NotBlank
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 12, scale = 2)
    @NotNull @DecimalMin("0.01")
    private BigDecimal targetAmount;

    @Column(name = "saved_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal savedAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @NotBlank
    private String currency;

    @Column(length = 300)
    private String description;

    /** Optional target completion date. */
    @Column(name = "deadline")
    private LocalDate deadline;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority = Priority.MEDIUM;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (savedAmount == null) savedAmount = BigDecimal.ZERO;
    }

    public double getProgressPercentage() {
        if (targetAmount == null || targetAmount.compareTo(BigDecimal.ZERO) == 0) return 0;
        double pct = savedAmount.doubleValue() / targetAmount.doubleValue() * 100.0;
        return Math.min(pct, 100.0);
    }

    public boolean isCompleted() {
        return savedAmount.compareTo(targetAmount) >= 0;
    }
}
