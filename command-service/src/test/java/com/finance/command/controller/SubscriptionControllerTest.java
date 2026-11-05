package com.finance.command.controller;

import com.finance.command.service.SubscriptionDetectorService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Read-side GET moved to query-service; see QuerySubscriptionControllerTest.
 */
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
    @DisplayName("DELETE /subscriptions/{id}/deactivate: deactivates recurring payment alert")
    void deactivate_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/upsert/subscriptions/1/deactivate")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk());
    }
}
