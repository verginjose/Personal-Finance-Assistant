package com.upsertservice.repository;

import com.upsertservice.model.ExpenseTransactionLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseTransactionLinkRepository extends JpaRepository<ExpenseTransactionLink, Long> {
    List<ExpenseTransactionLink> findBySharedExpenseId(Long sharedExpenseId);
    void deleteBySharedExpenseId(Long sharedExpenseId);
    boolean existsByTransactionEntryId(Long transactionEntryId);
}
