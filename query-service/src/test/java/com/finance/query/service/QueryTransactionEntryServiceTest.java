package com.finance.query.service;

import com.finance.query.model.Category;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.TransactionEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryTransactionEntryService — summary")
class QueryTransactionEntryServiceTest {

    @Mock TransactionEntryRepository repository;

    @InjectMocks QueryTransactionEntryService service;

    private TransactionEntry entry(TransactionType type, String amount, Category category, LocalDateTime at) {
        TransactionEntry e = new TransactionEntry();
        e.setType(type);
        e.setAmount(new BigDecimal(amount));
        e.setCategory(category);
        e.setCreatedAt(at);
        return e;
    }

    @Test
    @DisplayName("summary: honours the date range and excludes settlements from both totals")
    void summary_filtersRangeAndSettlement() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                entry(TransactionType.INCOME,  "10000", Category.SALARY,       LocalDateTime.of(2026, 11, 1, 9, 0)),
                entry(TransactionType.EXPENSE, "2000",  Category.RESTAURANTS,  LocalDateTime.of(2026, 11, 2, 9, 0)),
                entry(TransactionType.EXPENSE, "500",   Category.SETTLEMENT,   LocalDateTime.of(2026, 11, 3, 9, 0)),
                entry(TransactionType.EXPENSE, "3000",  Category.RENT,         LocalDateTime.of(2026, 10, 1, 9, 0))
        ));

        Map<String, Object> nov = service.getSummary(userId,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        assertThat(nov.get("totalIncome")).isEqualTo(new BigDecimal("10000"));
        assertThat(nov.get("totalExpense")).isEqualTo(new BigDecimal("2000"));
        assertThat(nov.get("netBalance")).isEqualTo(new BigDecimal("8000"));
        assertThat(nov.get("totalCount")).isEqualTo(2);

        Map<String, Object> allTime = service.getSummary(userId);
        assertThat(allTime.get("totalExpense")).isEqualTo(new BigDecimal("5000"));
        assertThat(allTime.get("totalCount")).isEqualTo(3);
    }
}
