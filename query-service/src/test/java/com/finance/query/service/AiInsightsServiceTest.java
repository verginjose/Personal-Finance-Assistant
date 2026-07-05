package com.finance.query.service;

import com.finance.query.dto.AiInsightResponse;
import com.finance.query.dto.CategoryRow;
import com.finance.query.model.Category;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.TransactionEntryRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiInsightsService — Unit Tests")
class AiInsightsServiceTest {

    @Mock TransactionEntryRepository repository;

    @InjectMocks AiInsightsService service;

    private UUID userId;

    @BeforeEach
    void setUp() { userId = UUID.randomUUID(); }

    // ── buildRuleBasedInsights ────────────────────────────────────────────────

    @Test
    @DisplayName("buildRuleBasedInsights: negative balance → WARNING insight")
    void buildRuleBasedInsights_negativeBalance_returnsWarning() {
        BigDecimal income  = new BigDecimal("20000");
        BigDecimal expense = new BigDecimal("25000");

        AiInsightResponse res = service.buildRuleBasedInsights(income, expense);

        assertThat(res.getInsights()).isNotEmpty();
        assertThat(res.getInsights().get(0).getType()).isEqualTo("WARNING");
        assertThat(res.getSummary()).contains("overspent");
    }

    @Test
    @DisplayName("buildRuleBasedInsights: good savings rate ≥20% → ACHIEVEMENT insight")
    void buildRuleBasedInsights_highSavingsRate_returnsAchievement() {
        BigDecimal income  = new BigDecimal("50000");
        BigDecimal expense = new BigDecimal("30000"); // 40% savings rate

        AiInsightResponse res = service.buildRuleBasedInsights(income, expense);

        assertThat(res.getInsights()).anyMatch(i -> "ACHIEVEMENT".equals(i.getType()));
        assertThat(res.getSummary()).contains("positive");
    }

    @Test
    @DisplayName("buildRuleBasedInsights: low savings rate < 20% → TIP insight")
    void buildRuleBasedInsights_lowSavingsRate_returnsTip() {
        BigDecimal income  = new BigDecimal("50000");
        BigDecimal expense = new BigDecimal("45000"); // 10% savings rate

        AiInsightResponse res = service.buildRuleBasedInsights(income, expense);

        assertThat(res.getInsights()).anyMatch(i -> "TIP".equals(i.getType()));
    }

    @Test
    @DisplayName("buildRuleBasedInsights: always includes tracking reminder insight")
    void buildRuleBasedInsights_alwaysIncludesTrackingTip() {
        AiInsightResponse res = service.buildRuleBasedInsights(
                new BigDecimal("10000"), new BigDecimal("8000"));

        assertThat(res.getInsights().stream().anyMatch(i -> i.getPriority() == 5)).isTrue();
    }

    // ── buildPrompt ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildPrompt: includes income, expense, and net balance")
    void buildPrompt_containsFinancialSummary() {
        BigDecimal income  = new BigDecimal("30000");
        BigDecimal expense = new BigDecimal("20000");
        List<CategoryRow> cats = new ArrayList<>();
        final Category category= Category.valueOf("FOOD_AND_DINING");
        final BigDecimal amount=new BigDecimal("8000");
        cats.add(new CategoryRow() {
            @Override
            public Category getCategory() {
                return category;
            }

            @Override
            public BigDecimal getTotalAmount() {
                return amount;
            }

            @Override
            public Long getTransactionCount() {
                return 0L;
            }
        });

        String prompt = service.buildPrompt(income, expense, cats, Collections.emptyList(), Collections.emptyList());

        assertThat(prompt).contains("30000");
        assertThat(prompt).contains("20000");
        assertThat(prompt).contains("10000"); // net
        assertThat(prompt).contains("FOOD_AND_DINING");
    }

    @Test
    @DisplayName("buildPrompt: works with empty category list")
    void buildPrompt_emptyCategories_noException() {
        assertThatCode(() -> service.buildPrompt(
                BigDecimal.valueOf(5000), BigDecimal.valueOf(3000), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()))
                .doesNotThrowAnyException();
    }

    // ── generateInsights — no transactions ───────────────────────────────────

    @Test
    @DisplayName("generateInsights: returns default response when user has no transactions")
    void generateInsights_noTransactions_returnsDefaultMessage() {
        when(repository.getTotalAmountByTypeAndDateRange(any(), ArgumentMatchers.eq(TransactionType.INCOME), any(), any()))
                .thenReturn(null);
        when(repository.getTotalAmountByTypeAndDateRange(any(), ArgumentMatchers.eq(TransactionType.EXPENSE), any(), any()))
                .thenReturn(null);
        when(repository.getCategoryAnalyticsByDateRange(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        AiInsightResponse res = service.generateInsights(userId);

        assertThat(res.getSummary()).containsIgnoringCase("transaction");
        assertThat(res.getInsights()).hasSize(1);
    }
}
