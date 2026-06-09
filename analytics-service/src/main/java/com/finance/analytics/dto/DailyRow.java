package com.finance.analytics.dto;

import java.math.BigDecimal;
import java.sql.Date;

/** Spring Data projection for daily aggregation queries. */
public interface DailyRow {
    Date getDay();
    BigDecimal getIncomeAmount();
    BigDecimal getExpenseAmount();
    Long getTransactionCount();
}