package com.upsertservice.model;
public enum IncomeCategory {
    SALARY("Salary"),
    BUSINESS("Business"),
    INVESTMENTS("Investments"),
    GIFTS("Gifts"),
    FREELANCE("Freelance"),
    RENTAL_INCOME("Rental Income"),
    INTEREST("Interest"),
    OTHERS("Others");

    private final String displayName;

    IncomeCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

