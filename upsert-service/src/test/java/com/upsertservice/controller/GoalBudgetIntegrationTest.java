package com.upsertservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upsertservice.dto.CategoryBudgetRequest;
import com.upsertservice.dto.SavingsGoalRequest;
import com.upsertservice.model.Category;
import com.upsertservice.model.RecurringPeriod;
import com.upsertservice.repository.CategoryBudgetRepository;
import com.upsertservice.repository.SavingsGoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("GoalBudget — Integration Tests")
public class GoalBudgetIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("finance_assistant")
            .withUsername("finance_user")
            .withPassword("finance_pass")
            .withInitScript("init.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", postgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", postgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "finance");
        // We mock Kafka to avoid needing a Kafka container for this test
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SavingsGoalRepository goalRepository;

    @Autowired
    private CategoryBudgetRepository budgetRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        goalRepository.deleteAll();
        budgetRepository.deleteAll();
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("E2E flow: Create and retrieve savings goal")
    void createAndGetGoal() throws Exception {
        SavingsGoalRequest request = new SavingsGoalRequest();
        request.setUserId(userId);
        request.setName("Vacation");
        request.setTargetAmount(new BigDecimal("5000.00"));
        request.setCurrency("USD");

        // 1. Create Goal
        mockMvc.perform(post("/upsert/goals")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Vacation"))
                .andExpect(jsonPath("$.savedAmount").value(0.0));

        // 2. Assert in DB
        assertThat(goalRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)).hasSize(1);

        // 3. Get Goals
        mockMvc.perform(get("/upsert/goals")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Vacation"));
    }

    @Test
    @DisplayName("E2E flow: Create and retrieve category budget")
    void createAndGetBudget() throws Exception {
        CategoryBudgetRequest request = new CategoryBudgetRequest();
        request.setUserId(userId);
        request.setExpenseCategory(Category.TRAVEL);
        request.setBudgetAmount(new BigDecimal("1000.00"));
        request.setPeriod(RecurringPeriod.MONTHLY);
        request.setCurrency("USD");

        // 1. Create Budget
        mockMvc.perform(post("/upsert/budgets")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expenseCategory").value("TRAVEL"))
                .andExpect(jsonPath("$.status").value("SAFE"));

        // 2. Assert in DB
        assertThat(budgetRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)).hasSize(1);

        // 3. Get Budgets
        mockMvc.perform(get("/upsert/budgets")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].expenseCategory").value("TRAVEL"));
    }
}
