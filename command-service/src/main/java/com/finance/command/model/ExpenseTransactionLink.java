package com.finance.command.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shared_expense_id", nullable = false)
    @JsonIgnore
    private SharedExpense sharedExpense;

    @Column(name = "shared_expense_id", insertable = false, updatable = false)
    private Long sharedExpenseId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_entry_id")
    @JsonIgnore
    private TransactionEntry transactionEntry;

    @Column(name = "transaction_entry_id", insertable = false, updatable = false)
    private Long transactionEntryId;

    public ExpenseTransactionLink(Long id, Long sharedExpenseId, UUID userId, Long transactionEntryId) {
        this.id = id;
        this.sharedExpenseId = sharedExpenseId;
        this.userId = userId;
        this.transactionEntryId = transactionEntryId;
    }
}
