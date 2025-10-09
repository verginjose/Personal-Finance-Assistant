package com.finance.analytics.repository;

import com.finance.analytics.model.TransactionEntry;
import com.finance.analytics.model.TransactionType;
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

    // Basic queries without null parameter issues
    @Query(value = "SELECT " +
            "CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END as category, " +
            "SUM(t.amount) as totalAmount, " +
            "COUNT(t.id) as transactionCount " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 " +
            "GROUP BY CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END " +
            "ORDER BY totalAmount DESC", nativeQuery = true)
    List<Object[]> getAllCategoryAnalytics(UUID userId);

    @Query(value = "SELECT " +
            "CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END as category, " +
            "SUM(t.amount) as totalAmount, " +
            "COUNT(t.id) as transactionCount " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 AND t.type = ?2 " +
            "GROUP BY CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END " +
            "ORDER BY totalAmount DESC", nativeQuery = true)
    List<Object[]> getCategoryAnalyticsByType(UUID userId, String transactionType);

    @Query(value = "SELECT " +
            "CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END as category, " +
            "SUM(t.amount) as totalAmount, " +
            "COUNT(t.id) as transactionCount " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 AND t.created_at >= ?2 AND t.created_at <= ?3 " +
            "GROUP BY CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END " +
            "ORDER BY totalAmount DESC", nativeQuery = true)
    List<Object[]> getCategoryAnalyticsByDateRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate);

    @Query(value = "SELECT " +
            "CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END as category, " +
            "SUM(t.amount) as totalAmount, " +
            "COUNT(t.id) as transactionCount " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 AND t.type = ?2 AND t.created_at >= ?3 AND t.created_at <= ?4 " +
            "GROUP BY CASE WHEN t.type = 'EXPENSE' THEN t.expense_category ELSE t.income_category END " +
            "ORDER BY totalAmount DESC", nativeQuery = true)
    List<Object[]> getCategoryAnalyticsByTypeAndDateRange(UUID userId, String transactionType,
                                                          LocalDateTime startDate, LocalDateTime endDate);

    // Timeline Analytics - All variations
    @Query(value = "SELECT " +
            "DATE(t.created_at) as date, " +
            "SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE 0 END) as incomeAmount, " +
            "SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) as expenseAmount, " +
            "COUNT(t.id) as totalTransactions " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 " +
            "GROUP BY DATE(t.created_at) " +
            "ORDER BY DATE(t.created_at)", nativeQuery = true)
    List<Object[]> getAllDailyAnalytics(UUID userId);

    @Query(value = "SELECT " +
            "DATE(t.created_at) as date, " +
            "SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE 0 END) as incomeAmount, " +
            "SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) as expenseAmount, " +
            "COUNT(t.id) as totalTransactions " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 AND t.created_at >= ?2 AND t.created_at <= ?3 " +
            "GROUP BY DATE(t.created_at) " +
            "ORDER BY DATE(t.created_at)", nativeQuery = true)
    List<Object[]> getDailyAnalyticsByDateRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate);

    @Query(value = "SELECT " +
            "EXTRACT(YEAR FROM t.created_at) as year, " +
            "EXTRACT(MONTH FROM t.created_at) as month, " +
            "SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE 0 END) as incomeAmount, " +
            "SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) as expenseAmount, " +
            "COUNT(t.id) as totalTransactions " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 " +
            "GROUP BY EXTRACT(YEAR FROM t.created_at), EXTRACT(MONTH FROM t.created_at) " +
            "ORDER BY EXTRACT(YEAR FROM t.created_at), EXTRACT(MONTH FROM t.created_at)", nativeQuery = true)
    List<Object[]> getAllMonthlyAnalytics(UUID userId);

    @Query(value = "SELECT " +
            "EXTRACT(YEAR FROM t.created_at) as year, " +
            "EXTRACT(MONTH FROM t.created_at) as month, " +
            "SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE 0 END) as incomeAmount, " +
            "SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) as expenseAmount, " +
            "COUNT(t.id) as totalTransactions " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 AND t.created_at >= ?2 AND t.created_at <= ?3 " +
            "GROUP BY EXTRACT(YEAR FROM t.created_at), EXTRACT(MONTH FROM t.created_at) " +
            "ORDER BY EXTRACT(YEAR FROM t.created_at), EXTRACT(MONTH FROM t.created_at)", nativeQuery = true)
    List<Object[]> getMonthlyAnalyticsByDateRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate);

    @Query(value = "SELECT " +
            "EXTRACT(YEAR FROM t.created_at) as year, " +
            "SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE 0 END) as incomeAmount, " +
            "SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) as expenseAmount, " +
            "COUNT(t.id) as totalTransactions " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 " +
            "GROUP BY EXTRACT(YEAR FROM t.created_at) " +
            "ORDER BY EXTRACT(YEAR FROM t.created_at)", nativeQuery = true)
    List<Object[]> getAllYearlyAnalytics(UUID userId);

    @Query(value = "SELECT " +
            "EXTRACT(YEAR FROM t.created_at) as year, " +
            "SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE 0 END) as incomeAmount, " +
            "SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount ELSE 0 END) as expenseAmount, " +
            "COUNT(t.id) as totalTransactions " +
            "FROM transaction_entries t " +
            "WHERE t.user_id = ?1 AND t.created_at >= ?2 AND t.created_at <= ?3 " +
            "GROUP BY EXTRACT(YEAR FROM t.created_at) " +
            "ORDER BY EXTRACT(YEAR FROM t.created_at)", nativeQuery = true)
    List<Object[]> getYearlyAnalyticsByDateRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate);

    // Summary queries
    @Query(value = "SELECT SUM(t.amount) FROM transaction_entries t WHERE t.user_id = ?1 AND t.type = ?2",
            nativeQuery = true)
    BigDecimal getTotalAmountByType(UUID userId, String type);

    @Query(value = "SELECT SUM(t.amount) FROM transaction_entries t WHERE t.user_id = ?1 AND t.type = ?2 " +
            "AND t.created_at >= ?3 AND t.created_at <= ?4", nativeQuery = true)
    BigDecimal getTotalAmountByTypeAndDateRange(UUID userId, String type, LocalDateTime startDate, LocalDateTime endDate);
    Page<TransactionEntry> findByUserId(UUID userId, Pageable pageable);
}