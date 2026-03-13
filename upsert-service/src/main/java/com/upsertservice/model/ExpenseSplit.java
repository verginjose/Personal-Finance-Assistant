package com.upsertservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "expense_splits")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shared_expense_id", nullable = false)
    private Long sharedExpenseId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_name", length = 100)
    private String userName;

    /** Amount this user owes for this expense */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "is_settled")
    private boolean settled = false;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}
