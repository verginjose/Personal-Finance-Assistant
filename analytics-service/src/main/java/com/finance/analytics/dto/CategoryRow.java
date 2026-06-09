package com.finance.analytics.dto;

<<<<<<< Updated upstream
import com.finance.analytics.model.Category;

=======
>>>>>>> Stashed changes
import java.math.BigDecimal;

/** Spring Data projection for category-level aggregation queries. */
public interface CategoryRow {
<<<<<<< Updated upstream
    Category getCategory();
=======
    Object getCategory();
>>>>>>> Stashed changes
    BigDecimal getTotalAmount();
    Long getTransactionCount();
}