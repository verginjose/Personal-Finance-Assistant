package com.finance.analytics.model;

/**
 * Period for recurring transactions.
 * Used in conjunction with {@link TransactionEntry#} flag.
 */
public enum RecurringPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
