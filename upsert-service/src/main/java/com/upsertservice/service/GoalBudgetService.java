package com.upsertservice.service;

import com.upsertservice.dto.*;
import com.upsertservice.model.*;
import com.upsertservice.repository.CategoryBudgetRepository;
import com.upsertservice.repository.SavingsGoalRepository;
import com.upsertservice.repository.TransactionEntryRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalBudgetService {

    private final SavingsGoalRepository goalRepository;
    private final CategoryBudgetRepository budgetRepository;
    private final TransactionEntryRepository transactionRepository;
    private final com.upsertservice.repository.TransactionGoalAllocationRepository allocationRepository;

    // ── Savings Goals ─────────────────────────────────────────────────────────

    @Transactional
    public SavingsGoalResponse createGoal(SavingsGoalRequest request) {
        SavingsGoal goal = new SavingsGoal();
        goal.setUserId(request.getUserId());
        goal.setName(request.getName());
        goal.setTargetAmount(request.getTargetAmount());
        goal.setCurrency(request.getCurrency());
        goal.setDescription(request.getDescription());
        goal.setDeadline(request.getDeadline());
        goal.setPriority(request.getPriority() != null ? request.getPriority() : Priority.MEDIUM);
        goal.setSavedAmount(BigDecimal.ZERO);
        SavingsGoal saved = goalRepository.save(goal);
        log.info("Goal created: id={}, user={}", saved.getId(), saved.getUserId());
        return toGoalResponse(saved);
    }

    @Transactional
    public SavingsGoalResponse contributeToGoal(Long goalId, UUID userId, GoalContributionRequest payload) {
        SavingsGoal goal = findGoalOwnedBy(goalId, userId);
        
        // 1. Create Transaction Entry
        TransactionEntry entry = new TransactionEntry(
                userId,
                "Contribution to " + goal.getName(),
                payload.getAmount(),
                TransactionType.EXPENSE,
                payload.getCurrency() != null ? payload.getCurrency() : goal.getCurrency()
        );
        entry.setCategory(Category.GOAL);
        entry.setDescription(payload.getDescription());
        if (payload.getCreatedAt() != null) {
            entry.setCreatedAt(payload.getCreatedAt());
        }
        TransactionEntry savedEntry = transactionRepository.save(entry);
        
        // 2. Create Transaction Goal Allocation
        TransactionGoalAllocation tga = new TransactionGoalAllocation();
        tga.setTransaction(savedEntry);
        tga.setGoal(goal);
        tga.setAmount(payload.getAmount());
        allocationRepository.save(tga);
        
        // 3. Update Goal
        goal.setSavedAmount(goal.getSavedAmount().add(payload.getAmount()));
        if (goal.isCompleted() && goal.getCompletedAt() == null) {
            goal.setCompletedAt(LocalDateTime.now());
            log.info("Goal {} completed by user {}!", goalId, userId);
        }
        return toGoalResponse(goalRepository.save(goal));
    }

    @Transactional
    public void adjustGoalSavedAmount(Long goalId, UUID userId, BigDecimal amount) {
        SavingsGoal goal = findGoalOwnedBy(goalId, userId);
        goal.setSavedAmount(goal.getSavedAmount().add(amount));
        if (goal.isCompleted() && goal.getCompletedAt() == null) {
            goal.setCompletedAt(LocalDateTime.now());
        }
        goalRepository.save(goal);
    }

    @Transactional
    public void deleteGoal(Long goalId, UUID userId) {
        SavingsGoal goal = findGoalOwnedBy(goalId, userId);
        goal.setActive(false);
        goalRepository.save(goal);
    }

    @Transactional(readOnly = true)
    public List<SavingsGoalResponse> getGoals(UUID userId) {
        return goalRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .stream().map(this::toGoalResponse).collect(Collectors.toList());
    }

    // ── Category Budgets ──────────────────────────────────────────────────────

    @Transactional
    public BudgetUtilizationResponse createBudget(CategoryBudgetRequest request) {
        CategoryBudget budget = new CategoryBudget();
        budget.setUserId(request.getUserId());
        budget.setExpenseCategory(request.getExpenseCategory());
        budget.setBudgetAmount(request.getBudgetAmount());
        budget.setPeriod(request.getPeriod());
        budget.setCurrency(request.getCurrency());
        budget.setCarryForward(request.isCarryForward());
        budget.setCustomStartDate(request.getCustomStartDate());
        budget.setCustomEndDate(request.getCustomEndDate());
        CategoryBudget saved = budgetRepository.save(budget);
        log.info("Budget created: id={}, category={}", saved.getId(), saved.getExpenseCategory());
        return computeUtilization(saved);
    }

    @Transactional(readOnly = true)
    public List<BudgetUtilizationResponse> getBudgets(UUID userId) {
        return budgetRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .stream().map(this::computeUtilization).collect(Collectors.toList());
    }

    @Transactional
    public void deleteBudget(Long budgetId, UUID userId) {
        CategoryBudget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found: " + budgetId));
        if (!budget.getUserId().equals(userId)) throw new SecurityException("Not authorized");
        budget.setActive(false);
        budgetRepository.save(budget);
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
        LocalDateTime start = switch (budget.getPeriod()) {
            case WEEKLY  -> LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
            case MONTHLY -> LocalDate.now().withDayOfMonth(1).atStartOfDay();
            default      -> LocalDate.now().withDayOfMonth(1).atStartOfDay();
        };
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
