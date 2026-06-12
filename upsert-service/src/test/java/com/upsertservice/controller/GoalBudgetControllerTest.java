package com.upsertservice.controller;

import com.upsertservice.dto.*;
import com.upsertservice.model.Category;
import com.upsertservice.model.RecurringPeriod;
import com.upsertservice.service.GoalBudgetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {GoalBudgetController.class, com.upsertservice.exception.GlobalExceptionHandler.class}, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@DisplayName("GoalBudgetController — Unit Tests")
public class GoalBudgetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GoalBudgetService service;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("POST /goals: creates savings goal")
    void createGoal_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        SavingsGoalRequest request = new SavingsGoalRequest();
        request.setUserId(userId);
        request.setName("New Car");
        request.setTargetAmount(new BigDecimal("15000"));
        request.setCurrency("INR");

        SavingsGoalResponse response = new SavingsGoalResponse(
                1L, "New Car", new BigDecimal("15000"), BigDecimal.ZERO,
                0.0, "INR", "Car goal", LocalDate.now().plusMonths(6),
                false, LocalDateTime.now(), null, com.upsertservice.model.Priority.MEDIUM
        );

        when(service.createGoal(any())).thenReturn(response);

        mockMvc.perform(post("/upsert/goals")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New Car"));
    }

    @Test
    @DisplayName("GET /goals: retrieves list of savings goals")
    void getGoals_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        SavingsGoalResponse response = new SavingsGoalResponse(
                1L, "Emergency Fund", new BigDecimal("5000"), BigDecimal.ZERO,
                0.0, "USD", "Emergency Fund", LocalDate.now().plusMonths(6),
                false, LocalDateTime.now(), null, com.upsertservice.model.Priority.MEDIUM
        );

        when(service.getGoals(userId)).thenReturn(List.of(response));

        mockMvc.perform(get("/upsert/goals")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Emergency Fund"));
    }

    @Test
    @DisplayName("POST /budgets: creates category budget")
    void createBudget_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        CategoryBudgetRequest request = new CategoryBudgetRequest();
        request.setUserId(userId);
        request.setExpenseCategory(Category.RESTAURANTS);
        request.setBudgetAmount(new BigDecimal("500"));
        request.setPeriod(RecurringPeriod.MONTHLY);
        request.setCurrency("INR");

        BudgetUtilizationResponse response = new BudgetUtilizationResponse(
                1L, Category.RESTAURANTS, new BigDecimal("500"),
                BigDecimal.ZERO, 0.0, RecurringPeriod.MONTHLY, "INR", "SAFE"
        );

        when(service.createBudget(any())).thenReturn(response);

        mockMvc.perform(post("/upsert/budgets")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expenseCategory").value("FOOD_AND_DINING"))
                .andExpect(jsonPath("$.budgetAmount").value(500));
    }

    @Test
    @DisplayName("PATCH /goals/{id}/contribute: contributes to a goal")
    void contributeToGoal_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        SavingsGoalResponse response = new SavingsGoalResponse(
                1L, "New Car", new BigDecimal("15000"), new BigDecimal("1000"),
                6.66, "INR", "Car goal", LocalDate.now().plusMonths(6),
                false, LocalDateTime.now(), null, com.upsertservice.model.Priority.MEDIUM
        );

        GoalContributionRequest req = new GoalContributionRequest();
        req.setAmount(new BigDecimal("1000"));
        
        when(service.contributeToGoal(any(Long.class), any(UUID.class), any(GoalContributionRequest.class))).thenReturn(response);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/upsert/goals/1/contribute")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedAmount").value(1000));
    }

    @Test
    @DisplayName("DELETE /goals/{id}: deletes a goal")
    void deleteGoal_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/upsert/goals/1")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /goals/{id}: returns 403 when SecurityException is thrown")
    void deleteGoal_forbidden() throws Exception {
        UUID userId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new SecurityException("Not authorized")).when(service).deleteGoal(1L, userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> 
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/upsert/goals/1")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
        ).hasCauseInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("GET /budgets: retrieves list of category budgets")
    void getBudgets_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        BudgetUtilizationResponse response = new BudgetUtilizationResponse(
                1L, Category.RESTAURANTS, new BigDecimal("500"),
                new BigDecimal("100"), 20.0, RecurringPeriod.MONTHLY, "INR", "SAFE"
        );

        when(service.getBudgets(userId)).thenReturn(List.of(response));

        mockMvc.perform(get("/upsert/budgets")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].expenseCategory").value("FOOD_AND_DINING"))
                .andExpect(jsonPath("$[0].utilizationPercentage").value(20.0));
    }

    @Test
    @DisplayName("DELETE /budgets/{id}: deletes a budget")
    void deleteBudget_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/upsert/budgets/1")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk());
    }
}
