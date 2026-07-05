package com.finance.command.controller;

import com.finance.command.dto.CreateEntryRequest;
import com.finance.command.dto.CreateEntryResponse;
import com.finance.command.model.Category;
import com.finance.command.model.TransactionType;
import com.finance.command.service.TransactionEntryService;
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
import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TransactionEntryController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@DisplayName("TransactionEntryController — Unit Tests")
public class TransactionEntryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionEntryService service;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("POST /upsert/create: returns 201 when X-User-Id matches request")
    void createEntry_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        CreateEntryRequest request = new CreateEntryRequest();
        request.setUserId(userId);
        request.setName("Salary");
        request.setAmount(new BigDecimal("5000.00"));
        request.setType(TransactionType.INCOME);
        request.setCategory(Category.SALARY);
        request.setCurrency("INR");

        CreateEntryResponse response = new CreateEntryResponse(
                1L, userId, "Salary", new BigDecimal("5000.00"),
                TransactionType.INCOME, Category.SALARY, "INR", "Monthly salary",
                false, null,
                LocalDateTime.now(), LocalDateTime.now()
        );

        when(service.createEntry(any(), any())).thenReturn(response);

        mockMvc.perform(post("/upsert/create")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("Salary"));
    }

    @Test
    @DisplayName("POST /upsert/create: returns 403 when X-User-Id mismatch")
    void createEntry_mismatchedUser_returns403() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        CreateEntryRequest request = new CreateEntryRequest();
        request.setUserId(userId);
        request.setName("Salary");
        request.setAmount(new BigDecimal("5000.00"));
        request.setType(TransactionType.INCOME);
        request.setCategory(Category.SALARY);
        request.setCurrency("INR");

        mockMvc.perform(post("/upsert/create")
                        .header("X-User-Id", otherId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /upsert/delete/{id}: soft deletes transaction entry")
    void deleteEntry_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/upsert/delete/1")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Entry deleted successfully"));
    }

    @Test
    @DisplayName("GET /upsert/summary: returns summary map")
    void getSummary_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        Map<String, Object> summary = Map.of("totalIncome", 5000.0, "totalExpense", 1500.0);

        when(service.getSummary(userId)).thenReturn(summary);

        mockMvc.perform(get("/upsert/summary")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(5000.0));
    }
}
