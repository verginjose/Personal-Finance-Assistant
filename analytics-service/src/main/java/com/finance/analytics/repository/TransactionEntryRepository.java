package com.finance.analytics.repository;

import com.finance.analytics.dto.CategoryRow;
import com.finance.analytics.dto.DailyRow;
import com.finance.analytics.dto.MonthlyRow;
import com.finance.analytics.dto.YearlyRow;
import com.finance.analytics.model.Category;
import com.finance.analytics.model.TransactionEntry;
import com.finance.analytics.model.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionEntryRepository extends JpaRepository<TransactionEntry, Long> {

    // ── Pagination ────────────────────────────────────────────────────────────

    Page<TransactionEntry> findByUserId(UUID userId, Pageable pageable);

    List<TransactionEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<TransactionEntry> findByUserIdAndCategoryAndCreatedAtBetween(
            UUID userId, Category incomeCategory,
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    Page<TransactionEntry> findByUserIdAndTypeAndCreatedAtBetween(
            UUID userId, TransactionType type,
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    // ── Totals ────────────────────────────────────────────────────────────────

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type
            """)
    BigDecimal getTotalAmountByType(@Param("userId") UUID userId, @Param("type") TransactionType type);

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type
              AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal getTotalAmountByTypeAndDateRange(
            @Param("userId") UUID userId, @Param("type") TransactionType type,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.category = :category AND t.type = 'EXPENSE'
              AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal sumExpensesByCategory(
            @Param("userId") UUID userId, @Param("category") Category category,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // ── Category analytics — typed projections replace Object[] ──────────────

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS totalAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId
            GROUP BY t.category
            ORDER BY totalAmount DESC
            """)
    List<CategoryRow> getAllCategoryAnalytics(@Param("userId") UUID userId);

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS totalAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type
            GROUP BY t.category
            ORDER BY totalAmount DESC
            """)
    List<CategoryRow> getCategoryAnalyticsByType(
            @Param("userId") UUID userId, @Param("type") TransactionType type);

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS totalAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY t.category
            ORDER BY totalAmount DESC
            """)
    List<CategoryRow> getCategoryAnalyticsByDateRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS totalAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY t.category
            ORDER BY totalAmount DESC
            """)
    List<CategoryRow> getCategoryAnalyticsByTypeAndDateRange(
            @Param("userId") UUID userId, @Param("type") TransactionType type,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // ── Daily timeline — typed projections ────────────────────────────────────

    @Query(value = """
            SELECT CAST(t.created_at AS date) AS day,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(*) AS transactionCount
            FROM transaction_entries t
            WHERE t.user_id = :userId
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<DailyRow> getAllDailyAnalytics(@Param("userId") UUID userId);

    @Query(value = """
            SELECT CAST(t.created_at AS date) AS day,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(*) AS transactionCount
            FROM transaction_entries t
            WHERE t.user_id = :userId
              AND t.created_at BETWEEN :start AND :end
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<DailyRow> getDailyAnalyticsByDateRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // ── Monthly timeline ──────────────────────────────────────────────────────

    @Query("""
            SELECT YEAR(t.createdAt)  AS year,
                   MONTH(t.createdAt) AS month,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId
            GROUP BY YEAR(t.createdAt), MONTH(t.createdAt)
            ORDER BY year, month
            """)
    List<MonthlyRow> getAllMonthlyAnalytics(@Param("userId") UUID userId);

    @Query("""
            SELECT YEAR(t.createdAt)  AS year,
                   MONTH(t.createdAt) AS month,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY YEAR(t.createdAt), MONTH(t.createdAt)
            ORDER BY year, month
            """)
    List<MonthlyRow> getMonthlyAnalyticsByDateRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // ── Yearly timeline ───────────────────────────────────────────────────────

    @Query("""
            SELECT YEAR(t.createdAt) AS year,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId
            GROUP BY YEAR(t.createdAt)
            ORDER BY year
            """)
    List<YearlyRow> getAllYearlyAnalytics(@Param("userId") UUID userId);

    @Query("""
            SELECT YEAR(t.createdAt) AS year,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY YEAR(t.createdAt)
            ORDER BY year
            """)
    List<YearlyRow> getYearlyAnalyticsByDateRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT COUNT(t.id) FROM transaction_entries t WHERE t.user_id = ?1 AND t.deleted_at IS NULL",
            nativeQuery = true)
    long countByUserId(UUID userId);

}