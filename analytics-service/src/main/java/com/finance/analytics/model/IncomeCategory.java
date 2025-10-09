package com.finance.analytics.model;

import lombok.Getter;

@Getter
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

}