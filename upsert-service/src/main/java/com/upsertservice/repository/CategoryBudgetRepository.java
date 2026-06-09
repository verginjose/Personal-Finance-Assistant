package com.upsertservice.repository;

import com.upsertservice.model.CategoryBudget;
import com.upsertservice.model.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryBudgetRepository extends JpaRepository<CategoryBudget, Long> {
    List<CategoryBudget> findByUserIdAndActiveTrueOrderByCreatedAtDesc(UUID userId);
    Optional<CategoryBudget> findByUserIdAndExpenseCategoryAndActiveTrue(UUID userId, ExpenseCategory category);
}
