package com.upsertservice.service;

import com.upsertservice.dto.*;
import com.upsertservice.model.*;
import com.upsertservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoalBudgetService — Unit Tests")
class GoalBudgetServiceTest {

    @Mock SavingsGoalRepository goalRepository;
    @Mock CategoryBudgetRepository budgetRepository;
    @Mock TransactionEntryRepository transactionRepository;

    @InjectMocks GoalBudgetService service;

    private UUID userId;

    @BeforeEach
    void setUp() { userId = UUID.randomUUID(); }

    // ── Savings Goals ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("createGoal: saves goal with zero savedAmount")
    void createGoal_validRequest_savesGoalWithZeroSaved() {
        SavingsGoalRequest req = new SavingsGoalRequest();
        req.setUserId(userId);
        req.setName("MacBook");
        req.setTargetAmount(new BigDecimal("150000"));
        req.setCurrency("INR");

        SavingsGoal saved = new SavingsGoal();
        saved.setId(1L);
        saved.setUserId(userId);
        saved.setName("MacBook");
        saved.setTargetAmount(new BigDecimal("150000"));
        saved.setSavedAmount(BigDecimal.ZERO);
        saved.setCurrency("INR");
        saved.setCreatedAt(LocalDateTime.now());

        when(goalRepository.save(any())).thenReturn(saved);

        SavingsGoalResponse response = service.createGoal(req);

        assertThat(response.getName()).isEqualTo("MacBook");
        assertThat(response.getSavedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getProgressPercentage()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("contributeToGoal: increases savedAmount and marks completed when target reached")
    void contributeToGoal_reachesTarget_marksCompleted() {
        SavingsGoal goal = buildGoal(new BigDecimal("10000"), new BigDecimal("9000"));
        when(goalRepository.findById(1L)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SavingsGoalResponse res = service.contributeToGoal(1L, userId, new BigDecimal("1000"));

        assertThat(res.isCompleted()).isTrue();
        assertThat(res.getSavedAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(res.getProgressPercentage()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("contributeToGoal: partial contribution does not mark completed")
    void contributeToGoal_partialAmount_notCompleted() {
        SavingsGoal goal = buildGoal(new BigDecimal("10000"), BigDecimal.ZERO);
        when(goalRepository.findById(1L)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SavingsGoalResponse res = service.contributeToGoal(1L, userId, new BigDecimal("3000"));

        assertThat(res.isCompleted()).isFalse();
        assertThat(res.getProgressPercentage()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("deleteGoal: throws SecurityException when wrong user")
    void deleteGoal_wrongUser_throwsSecurityException() {
        SavingsGoal goal = buildGoal(new BigDecimal("5000"), BigDecimal.ZERO);
        goal.setUserId(UUID.randomUUID()); // different user
        when(goalRepository.findById(1L)).thenReturn(Optional.of(goal));

        assertThatThrownBy(() -> service.deleteGoal(1L, userId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("deleteGoal: sets active=false")
    void deleteGoal_validOwner_archivesGoal() {
        SavingsGoal goal = buildGoal(new BigDecimal("5000"), BigDecimal.ZERO);
        when(goalRepository.findById(1L)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.deleteGoal(1L, userId);

        assertThat(goal.isActive()).isFalse();
    }

    // ── Category Budgets ──────────────────────────────────────────────────────

    @Test
    @DisplayName("computeUtilization: returns SAFE when spent < 80% of budget")
    void computeUtilization_belowWarningThreshold_returnsSafe() {
        CategoryBudget budget = buildBudget(new BigDecimal("5000"), RecurringPeriod.MONTHLY);
        when(transactionRepository.sumExpensesByCategory(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("3000")); // 60%

        BudgetUtilizationResponse res = service.computeUtilization(budget);

        assertThat(res.getStatus()).isEqualTo("SAFE");
        assertThat(res.getUtilizationPercentage()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("computeUtilization: returns WARNING when spent ≥ 80% of budget")
    void computeUtilization_atWarningThreshold_returnsWarning() {
        CategoryBudget budget = buildBudget(new BigDecimal("5000"), RecurringPeriod.MONTHLY);
        when(transactionRepository.sumExpensesByCategory(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("4200")); // 84%

        BudgetUtilizationResponse res = service.computeUtilization(budget);

        assertThat(res.getStatus()).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("computeUtilization: returns EXCEEDED when spent ≥ 100% of budget")
    void computeUtilization_exceeded_returnsExceeded() {
        CategoryBudget budget = buildBudget(new BigDecimal("5000"), RecurringPeriod.MONTHLY);
        when(transactionRepository.sumExpensesByCategory(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("6000")); // 120%

        BudgetUtilizationResponse res = service.computeUtilization(budget);

        assertThat(res.getStatus()).isEqualTo("EXCEEDED");
    }

    @Test
    @DisplayName("computeUtilization: handles null spend (no transactions) gracefully")
    void computeUtilization_nullSpend_treatedAsZero() {
        CategoryBudget budget = buildBudget(new BigDecimal("5000"), RecurringPeriod.MONTHLY);
        when(transactionRepository.sumExpensesByCategory(any(), any(), any(), any()))
                .thenReturn(null);

        BudgetUtilizationResponse res = service.computeUtilization(budget);

        assertThat(res.getSpentAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(res.getStatus()).isEqualTo("SAFE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SavingsGoal buildGoal(BigDecimal target, BigDecimal saved) {
        SavingsGoal g = new SavingsGoal();
        g.setId(1L);
        g.setUserId(userId);
        g.setName("Test Goal");
        g.setTargetAmount(target);
        g.setSavedAmount(saved);
        g.setCurrency("INR");
        g.setActive(true);
        g.setCreatedAt(LocalDateTime.now());
        return g;
    }

    private CategoryBudget buildBudget(BigDecimal amount, RecurringPeriod period) {
        CategoryBudget b = new CategoryBudget();
        b.setId(1L);
        b.setUserId(userId);
        b.setExpenseCategory(Category.FOOD_AND_DINING);
        b.setBudgetAmount(amount);
        b.setPeriod(period);
        b.setCurrency("INR");
        b.setActive(true);
        return b;
    }
}
