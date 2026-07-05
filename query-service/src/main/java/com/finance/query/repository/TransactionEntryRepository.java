package com.finance.query.repository;

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
}