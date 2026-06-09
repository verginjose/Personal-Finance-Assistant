package com.finance.analytics.service;

import com.finance.analytics.dto.HealthScoreResponse;
import com.finance.analytics.repository.TransactionEntryRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthScoreService — Unit Tests")
class HealthScoreServiceTest {

    @Mock TransactionEntryRepository repository;

    @InjectMocks HealthScoreService service;

    private UUID userId;

    @BeforeEach
    void setUp() { userId = UUID.randomUUID(); }

    // ── calcSavingsScore ──────────────────────────────────────────────────────

    @Test
    @DisplayName("calcSavingsScore: 50%+ savings → 300 pts")
    void calcSavingsScore_highSavings_returnsMax() {
        assertThat(service.calcSavingsScore(
                new BigDecimal("100000"), new BigDecimal("40000"))).isEqualTo(300);
    }

    @Test
    @DisplayName("calcSavingsScore: expense > income → 0 pts")
    void calcSavingsScore_negativeNet_returnsZero() {
        assertThat(service.calcSavingsScore(
                new BigDecimal("10000"), new BigDecimal("15000"))).isEqualTo(0);
    }

    @Test
    @DisplayName("calcSavingsScore: zero income → 0 pts (no divide-by-zero)")
    void calcSavingsScore_zeroIncome_returnsZero() {
        assertThat(service.calcSavingsScore(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(0);
    }

    @Test
    @DisplayName("calcSavingsScore: 25% savings rate → ~150 pts")
    void calcSavingsScore_quarterSavings_returnsMidScore() {
        int score = service.calcSavingsScore(new BigDecimal("100000"), new BigDecimal("75000"));
        assertThat(score).isGreaterThan(100).isLessThan(200);
    }

    // ── calcDiversificationScore ──────────────────────────────────────────────

    @Test
    @DisplayName("calcDiversificationScore: 5+ categories → 200 pts")
    void calcDiversificationScore_fiveCategories_returnsMax() {
        List<Object[]> cats = buildCategories(5);
        assertThat(service.calcDiversificationScore(cats)).isEqualTo(200);
    }

    @Test
    @DisplayName("calcDiversificationScore: 2 categories → 80 pts")
    void calcDiversificationScore_twoCategories_returns80() {
        List<Object[]> cats = buildCategories(2);
        assertThat(service.calcDiversificationScore(cats)).isEqualTo(80);
    }

    @Test
    @DisplayName("calcDiversificationScore: no categories → 0 pts")
    void calcDiversificationScore_noCategories_returnsZero() {
        assertThat(service.calcDiversificationScore(Collections.emptyList())).isEqualTo(0);
    }

    // ── calcIncomeExpenseScore ────────────────────────────────────────────────

    @Test
    @DisplayName("calcIncomeExpenseScore: expense < 50% of income → 200 pts")
    void calcIncomeExpenseScore_lowExpenseRatio_returnsMax() {
        assertThat(service.calcIncomeExpenseScore(
                new BigDecimal("100000"), new BigDecimal("40000"))).isEqualTo(200);
    }

    @Test
    @DisplayName("calcIncomeExpenseScore: expense > 150% of income → 0 pts")
    void calcIncomeExpenseScore_veryHighExpense_returnsZero() {
        assertThat(service.calcIncomeExpenseScore(
                new BigDecimal("10000"), new BigDecimal("20000"))).isEqualTo(0);
    }

    // ── calculateScore (integration of all dimensions) ────────────────────────

    @Test
    @DisplayName("calculateScore: excellent financials → score ≥ 700, grade A or higher")
    void calculateScore_excellentFinancials_highScore() {
        when(repository.getTotalAmountByType(userId, "INCOME")).thenReturn(new BigDecimal("100000"));
        when(repository.getTotalAmountByType(userId, "EXPENSE")).thenReturn(new BigDecimal("30000"));
        when(repository.getAllCategoryAnalytics(userId)).thenReturn(buildCategories(6));
        when(repository.countByUserId(userId)).thenReturn(60L);

        HealthScoreResponse res = service.calculateScore(userId);

        assertThat(res.getTotalScore()).isGreaterThanOrEqualTo(700);
        assertThat(res.getGrade()).isIn("A+", "A", "B");
        assertThat(res.getBreakdown()).containsKeys("Savings Rate", "Diversification");
    }

    @Test
    @DisplayName("calculateScore: poor financials (over-spender) → score ≤ 400")
    void calculateScore_poorFinancials_lowScore() {
        when(repository.getTotalAmountByType(userId, "INCOME")).thenReturn(new BigDecimal("10000"));
        when(repository.getTotalAmountByType(userId, "EXPENSE")).thenReturn(new BigDecimal("30000"));
        when(repository.getAllCategoryAnalytics(userId)).thenReturn(buildCategories(1));
        when(repository.countByUserId(userId)).thenReturn(2L);

        HealthScoreResponse res = service.calculateScore(userId);

        assertThat(res.getTotalScore()).isLessThanOrEqualTo(400);
        assertThat(res.getGrade()).isIn("D", "F");
    }

    @Test
    @DisplayName("toGrade: boundary conditions are correct")
    void toGrade_boundaryValues() {
        assertThat(HealthScoreResponse.toGrade(900)).isEqualTo("A+");
        assertThat(HealthScoreResponse.toGrade(800)).isEqualTo("A");
        assertThat(HealthScoreResponse.toGrade(700)).isEqualTo("B");
        assertThat(HealthScoreResponse.toGrade(400)).isEqualTo("F");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Object[]> buildCategories(int count) {
        List<Object[]> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Object[]{"CAT_" + i, BigDecimal.valueOf(1000 * (i + 1)), (long)(i + 1)});
        }
        return list;
    }
}
