package com.finance.analytics.dto;

import java.math.BigDecimal;

/** Spring Data projection for yearly aggregation queries. */
public interface YearlyRow {
    Integer getYear();
    BigDecimal getIncomeAmount();
    BigDecimal getExpenseAmount();
    Long getTransactionCount();
}