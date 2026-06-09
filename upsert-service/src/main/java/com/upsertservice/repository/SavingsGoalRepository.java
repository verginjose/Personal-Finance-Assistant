package com.upsertservice.repository;

import com.upsertservice.model.SavingsGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {
    List<SavingsGoal> findByUserIdAndActiveTrueOrderByCreatedAtDesc(UUID userId);
    List<SavingsGoal> findByUserId(UUID userId);
}
