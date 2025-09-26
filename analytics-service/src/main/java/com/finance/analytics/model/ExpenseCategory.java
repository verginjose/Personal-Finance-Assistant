package com.finance.analytics.model;


public enum ExpenseCategory {
    FOOD_AND_DINING("Food & Dining"),
    TRANSPORTATION("Transportation"),
    SHOPPING("Shopping"),
    ENTERTAINMENT("Entertainment"),
    BILLS_AND_UTILITIES("Bills & Utilities"),
    HEALTHCARE("Healthcare"),
    TRAVEL("Travel"),
    EDUCATION("Education"),
    OTHERS("Others");

    private final String displayName;

    ExpenseCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
