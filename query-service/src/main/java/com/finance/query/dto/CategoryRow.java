package com.finance.query.dto;

import com.finance.query.model.Category;

import java.math.BigDecimal;

/** Spring Data projection for category-level aggregation queries. */
public interface CategoryRow {
    Category getCategory();
    BigDecimal getTotalAmount();
    Long getTransactionCount();
}