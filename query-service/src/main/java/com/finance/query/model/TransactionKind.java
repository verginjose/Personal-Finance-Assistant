package com.finance.query.model;

/** Direction remains INCOME/EXPENSE; kind determines financial reporting. */
public enum TransactionKind {
    STANDARD, TRANSFER, INVESTMENT, REFUND, REIMBURSEMENT;

    public boolean isRecovery() {
        return this == REFUND || this == REIMBURSEMENT;
    }
}
