package com.upsertservice.controller;

import com.upsertservice.dto.*;
import com.upsertservice.model.ExpenseCategory;
import com.upsertservice.model.RecurringPeriod;
import com.upsertservice.service.GoalBudgetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
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

@WebMvcTest(controllers = GoalBudgetController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@DisplayName("GoalBudgetController — Unit Tests")
public class GoalBudgetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GoalBudgetService service;

    @MockBean
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
                false, LocalDateTime.now(), null
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
                false, LocalDateTime.now(), null
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
        request.setExpenseCategory(ExpenseCategory.FOOD_AND_DINING);
        request.setBudgetAmount(new BigDecimal("500"));
        request.setPeriod(RecurringPeriod.MONTHLY);
        request.setCurrency("INR");

        BudgetUtilizationResponse response = new BudgetUtilizationResponse(
                1L, ExpenseCategory.FOOD_AND_DINING, new BigDecimal("500"),
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
}
