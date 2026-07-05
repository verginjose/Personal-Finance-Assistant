package com.finance.query.controller;

import com.finance.query.dto.*;
import com.finance.query.service.GoalBudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/upsert")
@RequiredArgsConstructor
@Tag(name = "Goals & Budgets", description = "Savings goals and category budget management")
public class QueryGoalBudgetController {

    private final GoalBudgetService service;

    @GetMapping("/goals")
    @Operation(summary = "Get all active savings goals")
    public ResponseEntity<List<SavingsGoalResponse>> getGoals(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            throw new SecurityException("User ID mismatch");
        }
        return ResponseEntity.ok(service.getGoals(userId));
    }

    @GetMapping("/budgets")
    @Operation(summary = "Get all active category budgets with current utilization")
    public ResponseEntity<List<BudgetUtilizationResponse>> getBudgets(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            throw new SecurityException("User ID mismatch");
        }
        return ResponseEntity.ok(service.getBudgets(userId));
    }
}
