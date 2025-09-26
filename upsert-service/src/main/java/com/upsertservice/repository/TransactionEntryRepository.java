package com.upsertservice.repository;

import com.upsertservice.model.TransactionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionEntryRepository extends JpaRepository<TransactionEntry, Long> {
    List<TransactionEntry> findByUserId(UUID userId);
    List<TransactionEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
