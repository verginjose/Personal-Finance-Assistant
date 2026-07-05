package com.finance.query.service;

import com.finance.query.dto.*;
import com.finance.query.model.*;
import com.finance.query.repository.CategoryBudgetRepository;
import com.finance.query.repository.SavingsGoalRepository;
import com.finance.query.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.finance.query.model.RecurringPeriod.WEEKLY;


@Slf4j
@Service
@RequiredArgsConstructor
public class GoalBudgetService {

    private final SavingsGoalRepository goalRepository;
    private final CategoryBudgetRepository budgetRepository;
    private final TransactionEntryRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<SavingsGoalResponse> getGoals(UUID userId) {
        return goalRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .stream().map(this::toGoalResponse).collect(Collectors.toList());
    }

    // ── Category Budgets ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BudgetUtilizationResponse> getBudgets(UUID userId) {
        return budgetRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .stream().map(this::computeUtilization).collect(Collectors.toList());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    BudgetUtilizationResponse computeUtilization(CategoryBudget budget) {
        LocalDateTime[] range = getPeriodRange(budget);
        BigDecimal spent = transactionRepository.sumExpensesByCategory(
                budget.getUserId(), budget.getExpenseCategory(), range[0], range[1]);
        if (spent == null) spent = BigDecimal.ZERO;

        double pct = 0;
        if (budget.getBudgetAmount().compareTo(BigDecimal.ZERO) > 0) {
            pct = spent.divide(budget.getBudgetAmount(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        return new BudgetUtilizationResponse(
                budget.getId(), budget.getExpenseCategory(),
                budget.getBudgetAmount(), spent,
                Math.min(pct, 200.0),   // cap at 200% for display
                budget.getPeriod(), budget.getCurrency(),
                BudgetUtilizationResponse.deriveStatus(pct));
    }

    private LocalDateTime[] getPeriodRange(CategoryBudget budget) {
        if (budget.getCustomStartDate() != null && budget.getCustomEndDate() != null) {
            return new LocalDateTime[]{
                    budget.getCustomStartDate().atStartOfDay(),
                    budget.getCustomEndDate().atTime(23, 59, 59)
            };
        }
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = budget.getPeriod() == WEEKLY ? LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay()
                :LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return new LocalDateTime[]{start, end};
    }

    private SavingsGoal findGoalOwnedBy(Long goalId, UUID userId) {
        SavingsGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found: " + goalId));
        if (!goal.getUserId().equals(userId)) throw new SecurityException("Not authorized");
        return goal;
    }

    private SavingsGoalResponse toGoalResponse(SavingsGoal g) {
        return new SavingsGoalResponse(
                g.getId(), g.getName(), g.getTargetAmount(), g.getSavedAmount(),
                g.getProgressPercentage(), g.getCurrency(), g.getDescription(),
                g.getDeadline(), g.isCompleted(), g.getCreatedAt(), g.getCompletedAt(),
                g.getPriority());
    }
}
