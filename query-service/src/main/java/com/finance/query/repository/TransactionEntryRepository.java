package com.finance.query.repository;

import com.finance.query.dto.CategoryRow;
import com.finance.query.dto.DailyRow;
import com.finance.query.dto.MonthlyRow;
import com.finance.query.dto.YearlyRow;
import com.finance.query.model.Category;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;



@Repository
public interface TransactionEntryRepository extends JpaRepository<TransactionEntry, Long> {

    // ── Basic listing ─────────────────────────────────────────────────────────

    List<TransactionEntry> findByUserIdAndCreatedAtBetween(UUID userId, LocalDateTime start, LocalDateTime end);

    Page<TransactionEntry> findByUserId(UUID userId, Pageable pageable);

    Page<TransactionEntry> findByUserIdAndCategoryAndCreatedAtBetween(
            UUID userId, Category category,
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    Page<TransactionEntry> findByUserIdAndTypeAndCreatedAtBetween(
            UUID userId, TransactionType type,
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<TransactionEntry> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);

    Page<TransactionEntry> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<TransactionEntry> findByUserIdAndTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID userId, TransactionType type, Pageable pageable);

    Optional<TransactionEntry> findByIdAndDeletedAtIsNull(Long id);

    @Query("SELECT t FROM TransactionEntry t WHERE t.id = :id AND t.userId = :userId AND t.deletedAt IS NULL")
    Optional<TransactionEntry> findByIdAndUserIdAndDeletedAtIsNull(@Param("id") Long id, @Param("userId") UUID userId);

    List<TransactionEntry> findByRecurringTrueAndDeletedAtIsNullAndNextRunDateLessThanEqual(java.time.LocalDateTime nextRunDate);

    Page<TransactionEntry> findByRecurringTrueAndDeletedAtIsNullAndNextRunDateLessThanEqual(java.time.LocalDateTime nextRunDate, Pageable pageable);

    @Query("SELECT t FROM TransactionEntry t JOIN TransactionGoalAllocation a ON t.id = a.transactionId WHERE a.goalId = :goalId AND t.userId = :userId AND t.deletedAt IS NULL ORDER BY t.createdAt DESC")
    List<TransactionEntry> findByGoalIdAndUserId(@Param("goalId") Long goalId, @Param("userId") UUID userId);

    @Query("SELECT t FROM TransactionEntry t JOIN TransactionGoalAllocation a ON t.id = a.transactionId WHERE a.goalId = :goalId AND t.userId = :userId AND t.deletedAt IS NULL ORDER BY t.createdAt DESC")
    Page<TransactionEntry> findByGoalIdAndUserId(@Param("goalId") Long goalId, @Param("userId") UUID userId, Pageable pageable);

    // ── Date-range queries ────────────────────────────────────────────────────

    @Query("""
            SELECT t FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.deletedAt IS NULL
              AND (cast(:type as string) IS NULL OR t.type = :type)
              AND (cast(:start as timestamp) IS NULL OR t.createdAt >= :start)
              AND (cast(:end as timestamp) IS NULL OR t.createdAt <= :end)
            ORDER BY t.createdAt DESC
            """)
    Page<TransactionEntry> findByUserIdAndFilters(
            @Param("userId") UUID userId,
            @Param("type") TransactionType type,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    // ── Full-text search ──────────────────────────────────────────────────────

    @Query("""
            SELECT t FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.deletedAt IS NULL
              AND (LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY t.createdAt DESC
            """)
    Page<TransactionEntry> searchByUserId(
            @Param("userId") UUID userId,
            @Param("query") String query,
            Pageable pageable);

    // ── Recurring transactions ────────────────────────────────────────────────

    List<TransactionEntry> findByUserIdAndRecurringTrueAndDeletedAtIsNull(UUID userId);

    List<TransactionEntry> findAllByRecurringTrueAndDeletedAtIsNull();

    List<TransactionEntry> findByUserIdAndTypeAndCreatedAtAfterAndDeletedAtIsNull(
            UUID userId, TransactionType type, LocalDateTime after);

    // ── Budget utilization ────────────────────────────────────────────────────

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.deletedAt IS NULL
              AND t.type = com.finance.query.model.TransactionType.EXPENSE
              AND t.category = :category
              AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal sumExpensesByCategory(
            @Param("userId") UUID userId,
            @Param("category") Category category,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // ── Analytics Queries ──────────────────────────────────────────────────────

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type AND t.deletedAt IS NULL
            """)
    BigDecimal getTotalAmountByType(@Param("userId") UUID userId, @Param("type") TransactionType type);

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type AND t.deletedAt IS NULL
              AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal getTotalAmountByTypeAndDateRange(
            @Param("userId") UUID userId, @Param("type") TransactionType type,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS totalAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.deletedAt IS NULL
            GROUP BY t.category
            ORDER BY totalAmount DESC
            """)
    List<CategoryRow> getAllCategoryAnalytics(@Param("userId") UUID userId);

    @Query("""
            SELECT t.category AS category,
                   SUM(t.amount) AS totalAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.type = :type AND t.deletedAt IS NULL
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
            WHERE t.userId = :userId AND t.deletedAt IS NULL
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
            WHERE t.userId = :userId AND t.type = :type AND t.deletedAt IS NULL
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY t.category
            ORDER BY totalAmount DESC
            """)
    List<CategoryRow> getCategoryAnalyticsByTypeAndDateRange(
            @Param("userId") UUID userId, @Param("type") TransactionType type,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = """
            SELECT CAST(t.created_at AS date) AS day,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(*) AS transactionCount
            FROM transaction_entries t
            WHERE t.user_id = :userId AND t.deleted_at IS NULL
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
            WHERE t.user_id = :userId AND t.deleted_at IS NULL
              AND t.created_at BETWEEN :start AND :end
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<DailyRow> getDailyAnalyticsByDateRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT YEAR(t.createdAt)  AS year,
                   MONTH(t.createdAt) AS month,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.deletedAt IS NULL
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
            WHERE t.userId = :userId AND t.deletedAt IS NULL
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY YEAR(t.createdAt), MONTH(t.createdAt)
            ORDER BY year, month
            """)
    List<MonthlyRow> getMonthlyAnalyticsByDateRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT YEAR(t.createdAt) AS year,
                   SUM(CASE WHEN t.type = 'INCOME'  THEN t.amount ELSE 0 END) AS incomeAmount,
                   SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) AS expenseAmount,
                   COUNT(t) AS transactionCount
            FROM TransactionEntry t
            WHERE t.userId = :userId AND t.deletedAt IS NULL
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
            WHERE t.userId = :userId AND t.deletedAt IS NULL
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