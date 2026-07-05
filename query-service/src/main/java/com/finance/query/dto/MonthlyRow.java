package com.finance.query.dto;

import java.math.BigDecimal;

/** Spring Data projection for monthly aggregation queries. */
public interface MonthlyRow {
    Integer getYear();
    Integer getMonth();
    BigDecimal getIncomeAmount();
    BigDecimal getExpenseAmount();
    Long getTransactionCount();
}