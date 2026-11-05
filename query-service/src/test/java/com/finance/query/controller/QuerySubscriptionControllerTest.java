package com.finance.query.controller;

import com.finance.query.dto.SubscriptionResponse;
import com.finance.query.model.RecurringPeriod;
import com.finance.query.service.SubscriptionDetectorService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = QuerySubscriptionController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@DisplayName("QuerySubscriptionController — read side")
public class QuerySubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionDetectorService service;

    @Test
    @DisplayName("GET /upsert/subscriptions: returns detected recurring payments")
    void getSubscriptions_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        SubscriptionResponse response = new SubscriptionResponse(
                1L, "Netflix", new BigDecimal("649.00"), "INR",
                RecurringPeriod.MONTHLY, LocalDate.now().plusDays(5), 5, true
        );
        when(service.getSubscriptionsForUser(eq(userId), any())).thenReturn(List.of(response));

        mockMvc.perform(get("/upsert/subscriptions")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Netflix"))
                .andExpect(jsonPath("$[0].amount").value(649.00))
                .andExpect(jsonPath("$[0].daysUntilCharge").value(5));
    }

    @Test
    @DisplayName("GET /upsert/subscriptions: passes the caller's timezone through to the countdown")
    void getSubscriptions_passesTimezone() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.getSubscriptionsForUser(eq(userId), eq(330))).thenReturn(List.of());

        mockMvc.perform(get("/upsert/subscriptions")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString())
                        .param("tzOffsetMinutes", "330"))
                .andExpect(status().isOk());

        verify(service).getSubscriptionsForUser(userId, 330);
    }

    @Test
    @DisplayName("GET /upsert/subscriptions: another user's id is forbidden")
    void getSubscriptions_foreignUser_forbidden() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/upsert/subscriptions")
                        .header("X-User-Id", userId.toString())
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
