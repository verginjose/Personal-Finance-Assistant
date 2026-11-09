package com.finance.command.service;

import com.finance.command.model.*;
import com.finance.command.repository.ExpenseTransactionLinkRepository;
import com.finance.command.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;

/** Validates links under a row lock, including concurrent partial refunds. */
@Service
@RequiredArgsConstructor
public class TransactionAccountingService {
    private final TransactionEntryRepository repository;
    private final ExpenseTransactionLinkRepository groupLinks;

    private static final Set<Category> INCOME = Set.of(Category.SALARY, Category.COMMISSION,
            Category.CAPITAL_GAINS, Category.FREELANCE, Category.BUSINESS, Category.INVESTMENTS,
            Category.RENTAL_INCOME, Category.DIVIDENDS, Category.INTEREST, Category.BONUS,
            Category.PENSION, Category.GOVT_BENEFITS, Category.CASHBACK_REWARDS,
            Category.GIFTS_RECEIVED, Category.TAX_REFUND, Category.SIDE_HUSTLE, Category.OTHER_INCOME);

    public void validate(TransactionEntry entry) {
        if (entry.getTransactionKind() == null) entry.setTransactionKind(TransactionKind.STANDARD);
        if (entry.getAmount() == null || entry.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (entry.getSubcategory() != null) {
            String label = entry.getSubcategory().trim();
            if (label.length() > 80) throw new IllegalArgumentException("Subcategory cannot exceed 80 characters");
            entry.setSubcategory(label.isEmpty() ? null : label);
        }
        if (!entry.getTransactionKind().isRecovery()) {
            if (entry.getCategory() == null) throw new IllegalArgumentException("Category is required");
            if (entry.getOriginalTransactionId() != null) {
                throw new IllegalArgumentException("Only refunds and reimbursements can link to an expense");
            }
            if (entry.getTransactionKind() == TransactionKind.STANDARD
                    && !AccountingRules.MOVEMENT_CATEGORIES.contains(entry.getCategory())
                    && (entry.getType() == TransactionType.INCOME) != INCOME.contains(entry.getCategory())) {
                throw new IllegalArgumentException("Category does not match the transaction direction");
            }
            return;
        }
        if (entry.getType() != TransactionType.INCOME || entry.isRecurring()
                || entry.getOriginalTransactionId() == null
                || entry.getOriginalTransactionId().equals(entry.getId())) {
            throw new IllegalArgumentException("A refund or reimbursement must be a non-recurring incoming payment linked to an expense");
        }
        TransactionEntry original = repository.findAccountingEntryForUpdate(entry.getOriginalTransactionId(), entry.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Original expense not found"));
        if (original.getType() != TransactionType.EXPENSE || !original.isReportable()
                || original.getTransactionKind() != null && original.getTransactionKind().isRecovery()
                || !original.getCurrency().equalsIgnoreCase(entry.getCurrency())) {
            throw new IllegalArgumentException("The original transaction must be an expense in the same currency");
        }
        if (groupLinks.existsByTransactionEntryId(original.getId())) {
            throw new IllegalArgumentException("For a shared expense, adjust the expense in its group");
        }
        BigDecimal recovered = repository.sumRecoveries(original.getId(), entry.getId());
        if ((recovered == null ? BigDecimal.ZERO : recovered).add(entry.getAmount()).compareTo(original.getAmount()) > 0) {
            throw new IllegalArgumentException("Refunds and reimbursements cannot exceed the original expense");
        }
        entry.setCategory(original.getCategory());
        entry.setSubcategory(original.getSubcategory());
    }

    /** Prevent orphaned links or changed category/currency after recovery. */
    public void requireNoRecoveries(TransactionEntry entry) {
        repository.findAccountingEntryForUpdate(entry.getId(), entry.getUserId());
        BigDecimal recovered = repository.sumRecoveries(entry.getId(), null);
        if (recovered != null && recovered.signum() > 0) {
            throw new IllegalArgumentException("Remove linked refunds or reimbursements before changing or deleting the original expense");
        }
    }
}
