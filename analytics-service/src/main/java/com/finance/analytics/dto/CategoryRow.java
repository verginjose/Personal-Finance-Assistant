package com.finance.analytics.dto;

import com.finance.analytics.model.Category;

import java.math.BigDecimal;

/** Spring Data projection for category-level aggregation queries. */
public interface CategoryRow {
    Category getCategory();
    BigDecimal getTotalAmount();
    Long getTransactionCount();
}