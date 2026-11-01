package com.finance.query.controller;

import com.finance.query.service.QueryTransactionEntryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = QueryTransactionEntryController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@DisplayName("QueryTransactionEntryController — read-side error mapping")
public class QueryTransactionEntryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryTransactionEntryService service;

    @Test
    @DisplayName("GET /upsert/entries/{id}: an id that is not the caller's is a 404, not a 400")
    void foreignEntryId_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.getEntryById(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("Transaction entry with ID 42 not found"));

        mockMvc.perform(get("/upsert/entries/42")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("GET /upsert/entries/{id}: malformed input stays a 400")
    void otherIllegalArgument_returns400() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.getEntryById(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("amount must be positive"));

        mockMvc.perform(get("/upsert/entries/1")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad request"));
    }
}
