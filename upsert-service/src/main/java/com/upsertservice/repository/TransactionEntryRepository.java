package com.upsertservice.repository;

import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    /** Single entry by ID scoped to userId — prevents cross-user access. */
    Optional<TransactionEntry> findByIdAndUserIdAndDeletedAtIsNull(Long id, UUID userId);

    // ── Date-range queries ────────────────────────────────────────────────────

    @Query("SELECT t FROM TransactionEntry t WHERE t.userId = :userId " +
           "AND t.deletedAt IS NULL " +
           "AND (cast(:type as string) IS NULL OR t.type = :type) " +
           "AND (cast(:start as timestamp) IS NULL OR t.createdAt >= :start) " +
           "AND (cast(:end as timestamp) IS NULL OR t.createdAt <= :end) " +
           "ORDER BY t.createdAt DESC")
    Page<TransactionEntry> findByUserIdAndFilters(
            @Param("userId") UUID userId,
            @Param("type")   TransactionType type,
            @Param("start")  LocalDateTime start,
            @Param("end")    LocalDateTime end,
            Pageable pageable);

    // ── Full-text search ──────────────────────────────────────────────────────

    @Query("SELECT t FROM TransactionEntry t WHERE t.userId = :userId AND t.deletedAt IS NULL AND " +
           "(LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           " LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY t.createdAt DESC")
    Page<TransactionEntry> searchByUserId(@Param("userId") UUID userId,
                                          @Param("query") String query,
                                          Pageable pageable);

    // ── Recurring transactions ────────────────────────────────────────────────


    List<TransactionEntry> findByUserIdAndRecurringTrueAndDeletedAtIsNull(UUID userId);

    /** Used by the daily subscription detection scheduler — system-wide scan. */
    List<TransactionEntry> findAllByRecurringTrueAndDeletedAtIsNull();

    /** Pattern-based detection: fetch EXPENSE entries after a cutoff date. */
    List<TransactionEntry> findByUserIdAndTypeAndCreatedAtAfterAndDeletedAtIsNull(
            UUID userId, TransactionType type, java.time.LocalDateTime after);

    // ── Budget utilization ────────────────────────────────────────────────────

    /** Sum of expense amounts for a specific category within a date range. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntry t " +
           "WHERE t.userId = :userId AND t.deletedAt IS NULL AND t.type = 'EXPENSE' " +
           "AND t.expenseCategory = :category " +
           "AND t.createdAt BETWEEN :start AND :end")
    java.math.BigDecimal sumExpensesByCategory(
            @Param("userId") UUID userId,
            @Param("category") com.upsertservice.model.ExpenseCategory category,
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end);
}

