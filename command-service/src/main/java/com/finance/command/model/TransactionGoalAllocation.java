package com.finance.command.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_goal_allocations", schema = "finance", indexes = {
    @Index(name = "idx_allocation_transaction", columnList = "transaction_id"),
    @Index(name = "idx_allocation_goal", columnList = "goal_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionGoalAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    @JsonIgnore
    private TransactionEntry transaction;

    @Column(name = "transaction_id", insertable = false, updatable = false)
    private Long transactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goal_id", nullable = false)
    @JsonIgnore
    private SavingsGoal goal;

    @Column(name = "goal_id", insertable = false, updatable = false)
    private Long goalId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
