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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiInsightsService — Unit Tests")
class AiInsightsServiceTest {

    @Mock TransactionEntryRepository repository;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;

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
        assertThat(res.getSummary()).contains("exceeds income");
    }

    @Test
    @DisplayName("buildRuleBasedInsights: good savings rate ≥20% → ACHIEVEMENT insight")
    void buildRuleBasedInsights_highSavingsRate_returnsAchievement() {
        BigDecimal income  = new BigDecimal("50000");
        BigDecimal expense = new BigDecimal("30000"); // 40% savings rate

        AiInsightResponse res = service.buildRuleBasedInsights(income, expense);

        assertThat(res.getInsights()).anyMatch(i -> "ACHIEVEMENT".equals(i.getType()));
        assertThat(res.getSummary()).isNotBlank();
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
    @DisplayName("buildRuleBasedInsights: generic filler is not emitted")
    void buildRuleBasedInsights_noGenericFiller() {
        AiInsightResponse res = service.buildRuleBasedInsights(
                new BigDecimal("10000"), new BigDecimal("8000"));

        // Advice must cite the user's own data, not a template line.
        assertThat(res.getInsights()).noneMatch(i -> i.getTitle().equalsIgnoreCase("Track Every Expense"));
    }

    @Test
    @DisplayName("buildRuleBasedInsights: dominant category produces a category-specific tip")
    void buildRuleBasedInsights_dominantCategory_returnsCategoryTip() {
        CategoryRow restaurants = mock(CategoryRow.class);
        when(restaurants.getCategory()).thenReturn(Category.RESTAURANTS);
        when(restaurants.getTotalAmount()).thenReturn(new BigDecimal("8000"));

        AiInsightResponse res = service.buildRuleBasedInsights(
                new BigDecimal("50000"), new BigDecimal("10000"),
                List.of(restaurants), Collections.emptyList(), Collections.emptyList());

        assertThat(res.getInsights()).anyMatch(i ->
                i.getTitle().contains("Restaurants") && i.getMessage().contains("80%"));
    }

    @Test
    @DisplayName("normalizeInsights: sorts by priority and defaults invalid types to INFO")
    void normalizeInsights_sortsAndDefaults() {
        List<AiInsightResponse.Insight> insights = new ArrayList<>(List.of(
                new AiInsightResponse.Insight("Low", "later", "MYSTERY", 4),
                new AiInsightResponse.Insight("High", "now", "WARNING", 1)));

        AiInsightsService.normalizeInsights(insights);

        assertThat(insights.get(0).getTitle()).isEqualTo("High");
        assertThat(insights.get(0).getType()).isEqualTo("WARNING");
        assertThat(insights.get(1).getType()).isEqualTo("INFO");
    }

    @Test
    @DisplayName("regenerateInsights: evicts the cached advice before generating")
    void regenerateInsights_evictsCache() {
        when(cacheManager.getCache("ai-insights")).thenReturn(cache);

        service.regenerateInsights(userId);

        verify(cache).evict("v3:" + userId);
        verify(repository).getTotalAmountByType(userId, TransactionType.INCOME);
    }

    // ── buildPrompt ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildPrompt: includes income, expense, and net balance")
    void buildPrompt_containsFinancialSummary() {
        BigDecimal income  = new BigDecimal("30000");
        BigDecimal expense = new BigDecimal("20000");
        List<CategoryRow> cats = new ArrayList<>();
        final Category category= Category.valueOf("RESTAURANTS");
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
        assertThat(prompt).contains("RESTAURANTS");
    }

    @Test
    @DisplayName("buildPrompt: works with empty category list")
    void buildPrompt_emptyCategories_noException() {
        assertThatCode(() -> service.buildPrompt(
                BigDecimal.valueOf(5000), BigDecimal.valueOf(3000), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("buildPrompt: refers to goals by name, never by numeric id")
    void buildPrompt_usesGoalNameNotId() {
        GoalForecastingService.GoalForecast goal = new GoalForecastingService.GoalForecast();
        goal.setGoalId(1122L);
        goal.setGoalName("Japan Trip");
        goal.setSavedAmount(new BigDecimal("40000"));
        goal.setTargetAmount(new BigDecimal("200000"));
        goal.setMessage("On track to finish in 8 months.");

        String prompt = service.buildPrompt(
                new BigDecimal("50000"), new BigDecimal("30000"),
                Collections.emptyList(), List.of(goal), Collections.emptyList());

        assertThat(prompt).contains("Japan Trip");
        assertThat(prompt).doesNotContain("Goal 1122");
        assertThat(prompt).contains("Never mention numeric goal IDs");
    }

    @Test
    @DisplayName("buildRuleBasedInsights: names the stalled goal instead of a generic goal")
    void buildRuleBasedInsights_stalledGoal_usesName() {
        GoalForecastingService.GoalForecast goal = new GoalForecastingService.GoalForecast();
        goal.setGoalId(99L);
        goal.setGoalName("Emergency Fund");
        goal.setMonthlyVelocity(BigDecimal.ZERO);

        AiInsightResponse res = service.buildRuleBasedInsights(
                new BigDecimal("50000"), new BigDecimal("30000"),
                Collections.emptyList(), List.of(goal), Collections.emptyList());

        assertThat(res.getInsights()).anyMatch(i ->
                i.getTitle().contains("Emergency Fund")
                        && i.getMessage().contains("Emergency Fund"));
    }

    // ── generateInsights — no transactions ───────────────────────────────────

    @Test
    @DisplayName("generateInsights: returns default response when user has no transactions")
    void generateInsights_noTransactions_returnsDefaultMessage() {
        // generateInsights() now reads getTotalAmountByType()/getAllCategoryAnalytics();
        // unstubbed mocks return null/empty, which is the "no transactions" case.
        AiInsightResponse res = service.generateInsights(userId);

        assertThat(res.getSummary()).containsIgnoringCase("transaction");
        assertThat(res.getInsights()).hasSize(1);
    }
}
