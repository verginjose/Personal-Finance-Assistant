package com.upsertservice.repository;

import com.upsertservice.model.SharedExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SharedExpenseRepository extends JpaRepository<SharedExpense, Long> {
    List<SharedExpense> findByGroupIdOrderByCreatedAtDesc(Long groupId);
    List<SharedExpense> findByGroupIdAndPaidBy(Long groupId, UUID paidBy);
}
