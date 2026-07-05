package com.finance.command.controller;

import com.finance.command.dto.SubscriptionResponse;
import com.finance.command.model.RecurringPeriod;
import com.finance.command.service.SubscriptionDetectorService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SubscriptionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@DisplayName("SubscriptionController — Unit Tests")
public class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionDetectorService service;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("GET /subscriptions: returns detected recurring payments")
    void getSubscriptions_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        SubscriptionResponse response = new SubscriptionResponse(
                1L, "Netflix", new BigDecimal("649.00"), "INR",
                RecurringPeriod.MONTHLY, LocalDate.now().plusDays(5), 5, true
        );

        when(service.detectAndGetForUser(userId)).thenReturn(List.of(response));

        mockMvc.perform(get("/upsert/subscriptions")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Netflix"))
                .andExpect(jsonPath("$[0].amount").value(649.00));
    }

    @Test
    @DisplayName("DELETE /subscriptions/{id}/deactivate: deactivates recurring payment alert")
    void deactivate_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/upsert/subscriptions/1/deactivate")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk());
    }
}
