package com.finance.query.service;

import com.finance.query.model.Category;
import com.finance.query.model.ExpenseGroup;
import com.finance.query.model.ExpenseSplit;
import com.finance.query.model.ExpenseTransactionLink;
import com.finance.query.model.SharedExpense;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.ExpenseGroupRepository;
import com.finance.query.repository.ExpenseSplitRepository;
import com.finance.query.repository.ExpenseTransactionLinkRepository;
import com.finance.query.repository.SharedExpenseRepository;
import com.finance.query.repository.TransactionEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryTransactionEntryService — summary and group context")
class QueryTransactionEntryServiceTest {

    @Mock TransactionEntryRepository repository;
    @Mock ExpenseTransactionLinkRepository linkRepository;
    @Mock SharedExpenseRepository sharedExpenseRepository;
    @Mock ExpenseGroupRepository expenseGroupRepository;
    @Mock ExpenseSplitRepository expenseSplitRepository;

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

    @Test
    @DisplayName("entries: shared-expense rows carry group name, total and the user's share")
    void getEntriesByUserId_attachesGroupContext() {
        UUID userId = UUID.randomUUID();
        TransactionEntry entry = new TransactionEntry();
        entry.setId(7L);
        entry.setUserId(userId);
        entry.setType(TransactionType.EXPENSE);
        entry.setAmount(new BigDecimal("421.34"));

        when(repository.findByUserIdAndFilters(eq(userId), isNull(), isNull(), isNull(), isNull(),
                eq(PageRequest.of(0, 6))))
                .thenReturn(new PageImpl<>(List.of(entry)));

        ExpenseTransactionLink link = new ExpenseTransactionLink(1L, 10L, userId, 7L);
        when(linkRepository.findByTransactionEntryIdIn(List.of(7L))).thenReturn(List.of(link));

        SharedExpense expense = new SharedExpense();
        expense.setId(10L);
        expense.setGroupId(5L);
        expense.setAmount(new BigDecimal("2106.68"));
        when(sharedExpenseRepository.findAllById(List.of(10L))).thenReturn(List.of(expense));

        ExpenseGroup group = new ExpenseGroup();
        group.setId(5L);
        group.setName("Office lunch crew");
        when(expenseGroupRepository.findAllById(List.of(5L))).thenReturn(List.of(group));

        ExpenseSplit split = new ExpenseSplit();
        split.setSharedExpenseId(10L);
        split.setUserId(userId);
        split.setAmount(new BigDecimal("421.34"));
        when(expenseSplitRepository.findBySharedExpenseIdIn(List.of(10L))).thenReturn(List.of(split));

        var page = service.getEntriesByUserId(userId, null, null, null, null, 0, 6);

        TransactionEntry enriched = page.getContent().get(0);
        assertThat(enriched.getGroupId()).isEqualTo(5L);
        assertThat(enriched.getGroupName()).isEqualTo("Office lunch crew");
        assertThat(enriched.getTotalAmount()).isEqualByComparingTo("2106.68");
        assertThat(enriched.getMyShare()).isEqualByComparingTo("421.34");
    }
}
