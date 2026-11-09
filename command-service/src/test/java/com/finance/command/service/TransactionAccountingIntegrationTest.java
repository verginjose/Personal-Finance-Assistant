package com.finance.command.service;

import com.finance.command.model.*;
import com.finance.command.repository.TransactionEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=update", "spring.jpa.properties.hibernate.default_schema=finance"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TransactionAccountingService.class)
@Testcontainers
class TransactionAccountingIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15").withInitScript("init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired TransactionEntryRepository repository;
    @Autowired TransactionAccountingService accounting;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionEntry expense(UUID user) {
        TransactionEntry e = new TransactionEntry(user, "Groceries", new BigDecimal("200"), TransactionType.EXPENSE, "INR");
        e.setCategory(Category.GROCERIES);
        return repository.saveAndFlush(e);
    }
    private TransactionEntry refund(UUID user, Long original, String amount) {
        TransactionEntry e = new TransactionEntry(user, "Refund", new BigDecimal(amount), TransactionType.INCOME, "INR");
        e.setCategory(Category.OTHERS);
        e.setOriginalTransactionId(original);
        e.setTransactionKind(TransactionKind.REFUND);
        accounting.validate(e);
        return repository.saveAndFlush(e);
    }
    @Test void budgetsAndSummarySubtractPersistedPartialRefunds() {
        UUID user = UUID.randomUUID();
        TransactionEntry original = expense(user);
        refund(user, original.getId(), "50");
        assertThat(repository.sumByType(user, TransactionType.INCOME)).isZero();
        assertThat(repository.sumByType(user, TransactionType.EXPENSE)).isEqualByComparingTo("150");
        assertThat(repository.sumExpensesByCategory(user, Category.GROCERIES,
                original.getCreatedAt().minusDays(1), original.getCreatedAt().plusDays(1))).isEqualByComparingTo("150");
        assertThat(repository.sumExpensesByCategoryBatch(user, java.util.List.of(Category.GROCERIES),
                original.getCreatedAt().minusDays(1), original.getCreatedAt().plusDays(1))).singleElement().satisfies(row ->
                assertThat((BigDecimal) row[1]).isEqualByComparingTo("150"));
        assertThatThrownBy(() -> accounting.requireNoRecoveries(original)).hasMessageContaining("Remove linked refunds");
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPartialRefundsCannotExceedTheExpense() throws Exception {
        UUID user = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long original = tx.execute(status -> expense(user).getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> task = () -> {
                start.await();
                try {
                    tx.execute(status -> refund(user, original, "150"));
                    return true;
                } catch (IllegalArgumentException e) {
                    if (!e.getMessage().contains("cannot exceed")) throw e;
                    return false;
                }
            };
            Future<Boolean> a = executor.submit(task), b = executor.submit(task);
            start.countDown();
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(repository.sumRecoveries(original, null)).isEqualByComparingTo("150");
        } finally { executor.shutdownNow(); }
    }
}
