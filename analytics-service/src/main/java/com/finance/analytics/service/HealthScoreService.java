package com.finance.analytics.service;

import com.finance.analytics.dto.CategoryRow;
import com.finance.analytics.dto.HealthScoreResponse;
import com.finance.analytics.model.TransactionType;
import com.finance.analytics.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HealthScoreService {

    private final TransactionEntryRepository repository;

    // Cache score for 1 hour
    @Cacheable(value = "health-score", key = "#userId")
    public HealthScoreResponse calculateScore(UUID userId) {
        BigDecimal income  = safe(repository.getTotalAmountByType(userId, TransactionType.valueOf("INCOME")));
        BigDecimal expense = safe(repository.getTotalAmountByType(userId, TransactionType.valueOf("EXPENSE")));
        List<CategoryRow> categories = repository.getAllCategoryAnalytics(userId);
        long txCount = repository.countByUserId(userId);

        if (txCount == 0) {
            Map<String, Integer> emptyBreakdown = new LinkedHashMap<>();
            emptyBreakdown.put("Savings Rate", 0);
            emptyBreakdown.put("Diversification", 0);
            emptyBreakdown.put("Consistency", 0);
            emptyBreakdown.put("Income vs Expense", 0);
            emptyBreakdown.put("Tracking Habit", 0);

            return new HealthScoreResponse(0, "N/A", emptyBreakdown,
                    "Welcome! Add your first income and expenses to unlock your Financial Health Score.",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }

        int savingsScore       = calcSavingsScore(income, expense);      // 0–300
        int diversificationScore = calcDiversificationScore(categories); // 0–200
        int consistencyScore   = calcConsistencyScore(txCount);          // 0–200
        int incomeExpenseScore = calcIncomeExpenseScore(income, expense); // 0–200
        int trackingScore      = calcTrackingScore(txCount);             // 0–100

        int total = savingsScore + diversificationScore + consistencyScore
                + incomeExpenseScore + trackingScore;
        total = Math.min(total, 1000);

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("Savings Rate",       savingsScore);
        breakdown.put("Diversification",    diversificationScore);
        breakdown.put("Consistency",        consistencyScore);
        breakdown.put("Income vs Expense",  incomeExpenseScore);
        breakdown.put("Tracking Habit",     trackingScore);

        String summary = buildSummary(total, income, expense);
        log.info("Health score for user {}: {}", userId, total);

        return new HealthScoreResponse(total, HealthScoreResponse.toGrade(total), breakdown, summary,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    // ── Dimension calculators ─────────────────────────────────────────────────

    /**
     * Savings Rate = (income - expense) / income.
     * Max 300 pts. Negative savings = 0.
     */
    int calcSavingsScore(BigDecimal income, BigDecimal expense) {
        if (income.compareTo(BigDecimal.ZERO) == 0) return 0;
        double rate = income.subtract(expense).doubleValue() / income.doubleValue();
        if (rate <= 0)    return 0;
        if (rate >= 0.50) return 300;
        return (int) (rate / 0.50 * 300);
    }

    /**
     * Diversification = number of distinct expense categories used.
     * Max 200 pts. 5+ categories → full marks.
     */
    int calcDiversificationScore(List<CategoryRow> categories) {
        int count = categories.size();
        if (count == 0) return 0;
        if (count >= 5) return 200;
        return count * 40; // 40pts per category
    }

    /**
     * Consistency = has the user been tracking for a reasonable period?
     * Based on total transaction count. 50+ entries → full marks.
     */
    int calcConsistencyScore(long txCount) {
        if (txCount == 0) return 0;
        if (txCount >= 50) return 200;
        return (int) (txCount / 50.0 * 200);
    }

    /**
     * Income vs Expense: penalize if expense > income.
     * Max 200 pts.
     */
    int calcIncomeExpenseScore(BigDecimal income, BigDecimal expense) {
        if (income.compareTo(BigDecimal.ZERO) == 0) return 100; // no data, neutral
        double ratio = expense.doubleValue() / income.doubleValue();
        if (ratio <= 0.5)  return 200;
        if (ratio <= 0.8)  return 160;
        if (ratio <= 1.0)  return 100;
        if (ratio <= 1.5)  return 40;
        return 0;
    }

    /**
     * Tracking habit: reward users with many logged transactions.
     * Max 100 pts.
     */
    int calcTrackingScore(long txCount) {
        if (txCount == 0) return 0;
        if (txCount >= 20) return 100;
        return (int) (txCount / 20.0 * 100);
    }

    private String buildSummary(int score, BigDecimal income, BigDecimal expense) {
        String grade = HealthScoreResponse.toGrade(score);
        BigDecimal net = income.subtract(expense);
        return String.format(
            "Your Financial Health Score is %d (%s). Overall net balance: ₹%s. %s",
            score, grade, net.toPlainString(),
            score >= 700 ? "Great financial habits! Keep it up." :
            score >= 500 ? "Room for improvement — focus on savings and consistency." :
                           "Consider reducing expenses and tracking income more regularly."
        );
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
