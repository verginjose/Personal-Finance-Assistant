package com.finance.analytics.service;

import com.finance.analytics.model.CategoryBudget;
import com.finance.analytics.repository.CategoryBudgetRepository;
import com.finance.analytics.repository.TransactionEntryRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetTrendService {

    private final CategoryBudgetRepository budgetRepository;
    private final TransactionEntryRepository transactionRepository;

    @Data
    public static class BudgetTrend {
        private Long budgetId;
        private String category;
        private BigDecimal currentPeriodSpent;
        private BigDecimal previousPeriodSpent;
        private Double percentageChange;
        private String trend; // "INCREASED", "DECREASED", "NO_CHANGE"
    }

    public List<BudgetTrend> getTrends(UUID userId) {
        List<CategoryBudget> budgets = budgetRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        return budgets.stream().map(this::calculateTrend).collect(Collectors.toList());
    }

    private BudgetTrend calculateTrend(CategoryBudget budget) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime previousStart = currentStart.minusMonths(1);
        LocalDateTime previousEnd = currentStart.minusSeconds(1);

        // Current period spending
        BigDecimal currentSpent = transactionRepository.sumExpensesByCategory(
                budget.getUserId(), budget.getExpenseCategory(), currentStart, now);
        if (currentSpent == null) currentSpent = BigDecimal.ZERO;

        // Previous period spending
        BigDecimal prevSpent = transactionRepository.sumExpensesByCategory(
                budget.getUserId(), budget.getExpenseCategory(), previousStart, previousEnd);
        if (prevSpent == null) prevSpent = BigDecimal.ZERO;

        BudgetTrend trend = new BudgetTrend();
        trend.setBudgetId(budget.getId());
        trend.setCategory(budget.getExpenseCategory().name());
        trend.setCurrentPeriodSpent(currentSpent);
        trend.setPreviousPeriodSpent(prevSpent);

        if (prevSpent.compareTo(BigDecimal.ZERO) == 0) {
            trend.setPercentageChange(currentSpent.compareTo(BigDecimal.ZERO) > 0 ? 100.0 : 0.0);
            trend.setTrend(currentSpent.compareTo(BigDecimal.ZERO) > 0 ? "INCREASED" : "NO_CHANGE");
        } else {
            BigDecimal diff = currentSpent.subtract(prevSpent);
            double pct = diff.divide(prevSpent, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
            trend.setPercentageChange(pct);
            if (pct > 0) trend.setTrend("INCREASED");
            else if (pct < 0) trend.setTrend("DECREASED");
            else trend.setTrend("NO_CHANGE");
        }

        return trend;
    }
}
