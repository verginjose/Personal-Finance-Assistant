package com.finance.query.repository;

import com.finance.query.dto.CategoryRow;
import com.finance.query.dto.DailyRow;
import com.finance.query.dto.MonthlyRow;
import com.finance.query.dto.YearlyRow;
import com.finance.query.model.Category;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settlement entries live in the ledger (payer EXPENSE, receiver INCOME) but
 * must never appear in analytics aggregates: a settlement moves money between
 * people, so it is neither income nor spending. These queries are JPQL/native
 * SQL, so a unit test with a mocked repository cannot catch a missing filter.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.default_schema=finance"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TransactionEntryRepositorySettlementTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("finance_assistant")
            .withUsername("finance_user")
            .withPassword("finance_pass")
            .withInitScript("init.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TransactionEntryRepository repository;

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // Ordinary activity
        repository.save(entry(TransactionType.INCOME, Category.SALARY, "1000.00"));
        repository.save(entry(TransactionType.EXPENSE, Category.GROCERIES, "200.00"));
        // Settlements of the same amounts
        repository.save(entry(TransactionType.INCOME, Category.SETTLEMENT, "500.00"));
        repository.save(entry(TransactionType.EXPENSE, Category.SETTLEMENT, "500.00"));
    }

    private static TransactionEntry entry(TransactionType type, Category category, String amount) {
        TransactionEntry e = new TransactionEntry();
        e.setUserId(USER);
        e.setName(category.name());
        e.setAmount(new BigDecimal(amount));
        e.setType(type);
        e.setCategory(category);
        e.setCurrency("INR");
        return e;
    }

    @Test
    @DisplayName("totals exclude settlement entries")
    void totalsExcludeSettlements() {
        assertThat(repository.getTotalAmountByType(USER, TransactionType.INCOME))
                .isEqualByComparingTo("1000.00");
        assertThat(repository.getTotalAmountByType(USER, TransactionType.EXPENSE))
                .isEqualByComparingTo("200.00");
        assertThat(repository.getTotalAmountByTypeAndDateRange(USER, TransactionType.INCOME, START, END))
                .isEqualByComparingTo("1000.00");
        assertThat(repository.getTotalAmountByTypeAndDateRange(USER, TransactionType.EXPENSE, START, END))
                .isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("category aggregations exclude the SETTLEMENT category")
    void categoryAggregationsExcludeSettlements() {
        assertNoSettlement(repository.getCategoryAnalyticsByType(USER, TransactionType.EXPENSE), "200.00");
        assertNoSettlement(repository.getCategoryAnalyticsByDateRange(USER, START, END), null);
        assertNoSettlement(repository.getCategoryAnalyticsByTypeAndDateRange(
                USER, TransactionType.INCOME, START, END), "1000.00");
    }

    private static void assertNoSettlement(List<CategoryRow> rows, String expectedTotal) {
        assertThat(rows).noneMatch(r -> r.getCategory() == Category.SETTLEMENT);
        if (expectedTotal != null) {
            BigDecimal total = rows.stream()
                    .map(CategoryRow::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total).isEqualByComparingTo(expectedTotal);
        }
    }

    @Test
    @DisplayName("timeline aggregations exclude settlement amounts and counts")
    void timelineExcludesSettlements() {
        List<DailyRow> daily = repository.getDailyAnalytics(USER, START, END, 0);
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).getIncomeAmount()).isEqualByComparingTo("1000.00");
        assertThat(daily.get(0).getExpenseAmount()).isEqualByComparingTo("200.00");
        assertThat(daily.get(0).getTransactionCount()).isEqualTo(2L);

        List<MonthlyRow> monthly = repository.getMonthlyAnalytics(USER, START, END, 0);
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).getIncomeAmount()).isEqualByComparingTo("1000.00");
        assertThat(monthly.get(0).getExpenseAmount()).isEqualByComparingTo("200.00");
        assertThat(monthly.get(0).getTransactionCount()).isEqualTo(2L);

        List<YearlyRow> yearly = repository.getYearlyAnalytics(USER, START, END, 0);
        assertThat(yearly).hasSize(1);
        assertThat(yearly.get(0).getIncomeAmount()).isEqualByComparingTo("1000.00");
        assertThat(yearly.get(0).getExpenseAmount()).isEqualByComparingTo("200.00");
        assertThat(yearly.get(0).getTransactionCount()).isEqualTo(2L);
    }
    @Test
    void movementsAndRecoveriesHaveIdenticalTotalsAcrossEveryAggregate() {
        repository.save(entry(TransactionType.EXPENSE, Category.CREDIT_CARD_PAYMENT, "200.00"));
        repository.save(entry(TransactionType.EXPENSE, Category.MUTUAL_FUNDS_SIP, "500.00"));
        TransactionEntry transfer = entry(TransactionType.INCOME, Category.OTHER_INCOME, "900.00");
        transfer.setTransactionKind(com.finance.query.model.TransactionKind.TRANSFER);
        repository.save(transfer);
        TransactionEntry refund = entry(TransactionType.INCOME, Category.GROCERIES, "50.00");
        refund.setTransactionKind(com.finance.query.model.TransactionKind.REFUND);
        repository.save(refund);
        TransactionEntry reimbursement = entry(TransactionType.INCOME, Category.GROCERIES, "20.00");
        reimbursement.setTransactionKind(com.finance.query.model.TransactionKind.REIMBURSEMENT);
        repository.saveAndFlush(reimbursement);

        assertThat(repository.getTotalAmountByType(USER, TransactionType.INCOME)).isEqualByComparingTo("1000");
        assertThat(repository.getTotalAmountByType(USER, TransactionType.EXPENSE)).isEqualByComparingTo("130");
        assertThat(repository.getTotalAmountByTypeAndDateRange(USER, TransactionType.EXPENSE, START, END)).isEqualByComparingTo("130");
        assertThat(repository.sumExpensesByCategory(USER, Category.GROCERIES, START, END)).isEqualByComparingTo("130");
        assertThat(repository.getAllCategoryAnalytics(USER)).singleElement().satisfies(r -> {
            assertThat(r.getCategory()).isEqualTo(Category.GROCERIES);
            assertThat(r.getTotalAmount()).isEqualByComparingTo("130");
        });
        assertNoSettlement(repository.getExpenseCategoryAnalyticsByDateRange(USER, START, END), "130");
        assertNoSettlement(repository.getCategoryAnalyticsByType(USER, TransactionType.EXPENSE), "130");
        assertNoSettlement(repository.getCategoryAnalyticsByTypeAndDateRange(USER, TransactionType.EXPENSE, START, END), "130");
        assertThat(repository.getDailyAnalytics(USER, START, END, 330)).singleElement().satisfies(r -> {
            assertThat(r.getIncomeAmount()).isEqualByComparingTo("1000");
            assertThat(r.getExpenseAmount()).isEqualByComparingTo("130");
            assertThat(r.getTransactionCount()).isEqualTo(4);
        });
        assertThat(repository.getMonthlyAnalytics(USER, START, END, 330)).singleElement().satisfies(r ->
                assertThat(r.getExpenseAmount()).isEqualByComparingTo("130"));
        assertThat(repository.getYearlyAnalytics(USER, START, END, 330)).singleElement().satisfies(r ->
                assertThat(r.getExpenseAmount()).isEqualByComparingTo("130"));
        assertThat(repository.countByUserId(USER)).isEqualTo(4);
        assertThat(repository.countByUserIdAndDateRange(USER, START, END)).isEqualTo(4);
    }
}
