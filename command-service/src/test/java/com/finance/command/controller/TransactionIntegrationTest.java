package com.finance.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.command.dto.CreateEntryRequest;
import com.finance.command.dto.CreateEntryResponse;
import com.finance.command.model.Category;
import com.finance.command.model.TransactionType;
import com.finance.command.repository.TransactionEntryRepository;
import com.finance.command.service.TransactionEntryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("TransactionEntryService — Integration Tests with Testcontainers")
public class TransactionIntegrationTest {

    static {
        System.setProperty("docker.api.version", "1.40");
        System.setProperty("DOCKER_API_VERSION", "1.40");
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionEntryRepository repository;

    @Autowired
    private TransactionEntryService transactionEntryService;

    @BeforeEach
    void setupMock() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Two concurrent creates with one idempotency key persist exactly one row")
    void concurrentIdempotentCreates_persistOneRow() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        CreateEntryRequest request = new CreateEntryRequest();
        request.setUserId(userId);
        request.setName("Idempotent double submit");
        request.setAmount(new BigDecimal("777.00"));
        request.setType(TransactionType.EXPENSE);
        request.setCategory(Category.OTHERS);
        request.setCurrency("INR");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<CreateEntryResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transactionEntryService.createEntry(request, key);
                }));
            }
            start.countDown();

            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (var future : futures) {
                ids.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS).getId());
            }

            assertThat(ids).hasSize(2);
            assertThat(ids.get(0)).isEqualTo(ids.get(1));
            assertThat(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("End-to-End create, retrieve, search, soft-delete, and summary flow")
    void e2eTransactionFlow() throws Exception {
        UUID userId = UUID.randomUUID();

        // 1. Create a transaction
        CreateEntryRequest request = new CreateEntryRequest();
        request.setUserId(userId);
        request.setName("Salary payment");
        request.setAmount(new BigDecimal("5000.00"));
        request.setType(TransactionType.INCOME);
        request.setCategory(Category.SALARY);
        request.setCurrency("INR");
        request.setDescription("E2E Test salary entry");

        String responseStr = mockMvc.perform(post("/upsert/create")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Salary payment"))
                .andExpect(jsonPath("$.amount").value(5000.00))
                .andExpect(jsonPath("$.type").value("INCOME"))
                .andExpect(jsonPath("$.incomeCategory").value("SALARY"))
                .andReturn().getResponse().getContentAsString();

        CreateEntryResponse created = objectMapper.readValue(responseStr, CreateEntryResponse.class);
        Long id = created.getId();

        // Verify it was persisted in PostgreSQL database
        assertThat(repository.findByIdAndDeletedAtIsNull(id)).isPresent();

        // 2. Fetch list of transactions for this user
        mockMvc.perform(get("/upsert/entries")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].name").value("Salary payment"));

        // 3. Search for transaction
        mockMvc.perform(get("/upsert/search")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString())
                        .param("q", "payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id));

        // 4. Retrieve summary
        mockMvc.perform(get("/upsert/summary")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(5000.00))
                .andExpect(jsonPath("$.totalExpense").value(0.00));

        // 5. Soft-delete the transaction
        mockMvc.perform(delete("/upsert/delete/" + id)
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Entry deleted successfully"));

        // Verify it was soft deleted (no longer returned by findByIdAndDeletedAtIsNull)
        assertThat(repository.findByIdAndDeletedAtIsNull(id)).isEmpty();
    }
}
