package com.finance.command.model;

import java.math.BigDecimal;
import java.util.Set;

/** Keep SQL aggregates and in-memory summaries under the same accounting policy. */
public final class AccountingRules {
    private AccountingRules() {}

    public static final Set<Category> MOVEMENT_CATEGORIES = Set.of(
            Category.SETTLEMENT, Category.CREDIT_CARD_PAYMENT, Category.MUTUAL_FUNDS_SIP, Category.STOCKS_AND_TRADING, Category.CRYPTO, Category.EMERGENCY_FUND, Category.FIXED_DEPOSIT, Category.GOAL, Category.OWN_ACCOUNT_TRANSFER, Category.BORROWING, Category.LENDING, Category.LOAN_PRINCIPAL, Category.INVESTMENT_WITHDRAWAL);

    public static final String REPORTABLE_SQL =
            "coalesce(transaction_kind, 'STANDARD') not in ('TRANSFER', 'INVESTMENT')"
            + " and category not in ('SETTLEMENT', 'CREDIT_CARD_PAYMENT', 'MUTUAL_FUNDS_SIP', 'STOCKS_AND_TRADING', 'CRYPTO', 'EMERGENCY_FUND', 'FIXED_DEPOSIT', 'GOAL', 'OWN_ACCOUNT_TRANSFER', 'BORROWING', 'LENDING', 'LOAN_PRINCIPAL', 'INVESTMENT_WITHDRAWAL')";
    public static final String AMOUNT_SQL = "case when " + REPORTABLE_SQL
            + " then case when transaction_kind in ('REFUND', 'REIMBURSEMENT') then -amount else amount end else 0 end";
    public static final String TYPE_SQL =
            "case when transaction_kind in ('REFUND', 'REIMBURSEMENT') then 'EXPENSE' else type end";

    public static boolean isReportable(TransactionEntry entry) {
        return entry.getTransactionKind() != TransactionKind.TRANSFER
                && entry.getTransactionKind() != TransactionKind.INVESTMENT
                && !MOVEMENT_CATEGORIES.contains(entry.getCategory() == null ? Category.OTHERS : entry.getCategory());
    }

    public static BigDecimal amount(TransactionEntry entry) {
        if (!isReportable(entry)) return BigDecimal.ZERO;
        return entry.getTransactionKind() != null && entry.getTransactionKind().isRecovery()
                ? entry.getAmount().negate() : entry.getAmount();
    }

    public static TransactionType type(TransactionEntry entry) {
        return entry.getTransactionKind() != null && entry.getTransactionKind().isRecovery()
                ? TransactionType.EXPENSE : entry.getType();
    }
}
