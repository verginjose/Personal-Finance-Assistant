package com.finance.query.repository;

import com.finance.query.model.ExpenseTransactionLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseTransactionLinkRepository extends JpaRepository<ExpenseTransactionLink, Long> {
    List<ExpenseTransactionLink> findBySharedExpenseId(Long sharedExpenseId);
    void deleteBySharedExpenseId(Long sharedExpenseId);
    boolean existsByTransactionEntryId(Long transactionEntryId);
}
