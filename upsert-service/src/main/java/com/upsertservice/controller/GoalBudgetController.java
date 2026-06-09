package com.upsertservice.controller;

import com.upsertservice.dto.*;
import com.upsertservice.service.GoalBudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/upsert")
@RequiredArgsConstructor
@Tag(name = "Goals & Budgets", description = "Savings goals and category budget management")
public class GoalBudgetController {

    private final GoalBudgetService service;

    // ── Goals ─────────────────────────────────────────────────────────────────

    @PostMapping("/goals")
    @Operation(summary = "Create a savings goal")
    public ResponseEntity<SavingsGoalResponse> createGoal(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody SavingsGoalRequest request) {
        validateUser(xUserId, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGoal(request));
    }

    @GetMapping("/goals")
    @Operation(summary = "List savings goals for a user")
    public ResponseEntity<List<SavingsGoalResponse>> getGoals(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        validateUser(xUserId, userId);
        return ResponseEntity.ok(service.getGoals(userId));
    }

    @PatchMapping("/goals/{id}/contribute")
    @Operation(summary = "Add money towards a savings goal")
    public ResponseEntity<SavingsGoalResponse> contribute(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long id,
            @RequestParam UUID userId,
            @RequestParam BigDecimal amount) {
        validateUser(xUserId, userId);
        return ResponseEntity.ok(service.contributeToGoal(id, userId, amount));
    }

    @DeleteMapping("/goals/{id}")
    @Operation(summary = "Delete (archive) a savings goal")
    public ResponseEntity<Void> deleteGoal(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long id,
            @RequestParam UUID userId) {
        validateUser(xUserId, userId);
        service.deleteGoal(id, userId);
        return ResponseEntity.ok().build();
    }

    // ── Budgets ───────────────────────────────────────────────────────────────

    @PostMapping("/budgets")
    @Operation(summary = "Create a category budget")
    public ResponseEntity<BudgetUtilizationResponse> createBudget(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody CategoryBudgetRequest request) {
        validateUser(xUserId, request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBudget(request));
    }

    @GetMapping("/budgets")
    @Operation(summary = "List category budgets with live utilization")
    public ResponseEntity<List<BudgetUtilizationResponse>> getBudgets(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        validateUser(xUserId, userId);
        return ResponseEntity.ok(service.getBudgets(userId));
    }

    @DeleteMapping("/budgets/{id}")
    @Operation(summary = "Delete a budget")
    public ResponseEntity<Void> deleteBudget(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long id,
            @RequestParam UUID userId) {
        validateUser(xUserId, userId);
        service.deleteBudget(id, userId);
        return ResponseEntity.ok().build();
    }

    private void validateUser(String xUserId, UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            throw new SecurityException("User ID mismatch");
        }
    }
}
