package com.upsertservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "expense_transaction_links", schema = "groups",
        uniqueConstraints = @UniqueConstraint(columnNames = {"shared_expense_id", "user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseTransactionLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shared_expense_id", nullable = false)
    private Long sharedExpenseId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transaction_entry_id", nullable = false)
    private Long transactionEntryId;
}
