package com.upsertservice.repository;

import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionEntryRepository extends JpaRepository<TransactionEntry, Long> {

    List<TransactionEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<TransactionEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<TransactionEntry> findByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, TransactionType type, Pageable pageable);

    @Query("SELECT t FROM TransactionEntry t WHERE t.userId = :userId AND " +
           "(LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           " LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY t.createdAt DESC")
    Page<TransactionEntry> searchByUserId(@Param("userId") UUID userId,
                                          @Param("query") String query,
                                          Pageable pageable);
}
