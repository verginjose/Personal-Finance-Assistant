package com.finance.command.repository;

import com.finance.command.model.Category;
import com.finance.command.model.TransactionEntry;
import com.finance.command.model.TransactionType;
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
              AND t.type = :type
              AND t.category = :category
              AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal sumExpensesByCategory(
            @Param("userId") UUID userId,
            @Param("category") Category category,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // ── Summary aggregates (avoids full table scan in getSummary()) ────────────

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.deletedAt IS NULL
              AND t.type = :type
            """)
    BigDecimal sumByType(
            @Param("userId") UUID userId,
            @Param("type") TransactionType type);

    @Query("SELECT COUNT(t) FROM TransactionEntry t WHERE t.userId = :userId AND t.deletedAt IS NULL")
    long countActiveByUser(@Param("userId") UUID userId);

    // ── Batch budget utilization (eliminates N+1) ─────────────────────────────

    /**
     * Returns expense totals grouped by category for the given set of categories
     * within a date range — one query instead of one per budget.
     * Result: Object[] where [0] = Category enum, [1] = BigDecimal sum.
     */
    @Query("""
            SELECT t.category, COALESCE(SUM(t.amount), 0)
            FROM TransactionEntry t
            WHERE t.userId = :userId
              AND t.deletedAt IS NULL
              AND t.type = com.finance.command.model.TransactionType.EXPENSE
              AND t.category IN :categories
              AND t.createdAt BETWEEN :start AND :end
            GROUP BY t.category
            """)
    List<Object[]> sumExpensesByCategoryBatch(
            @Param("userId") UUID userId,
            @Param("categories") List<Category> categories,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}