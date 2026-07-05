package com.finance.command.controller;

import com.finance.command.dto.*;
import com.finance.command.model.*;
import com.finance.command.service.SplitService;
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

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SplitController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@DisplayName("SplitController — Unit Tests")
public class SplitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SplitService splitService;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("POST /upsert/groups: creates expense group")
    void createGroup_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("Trip to Goa");
        request.setDescription("Goa description");
        request.setCreatedBy(userId);
        request.setCurrency("INR");

        ExpenseGroup group = new ExpenseGroup(1L, "Trip to Goa", "Goa description", userId, "INR", LocalDateTime.now(), false, null, null, null);

        when(splitService.createGroup(any())).thenReturn(group);

        mockMvc.perform(post("/upsert/groups")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trip to Goa"));
    }

    @Test
    @DisplayName("GET /upsert/groups: returns user groups")
    void getUserGroups_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        ExpenseGroup group = new ExpenseGroup(1L, "Trip to Goa", "Goa description", userId, "INR", LocalDateTime.now(), false, null, null, null);

        when(splitService.getUserGroups(userId)).thenReturn(List.of(group));

        mockMvc.perform(get("/upsert/groups")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Trip to Goa"));
    }

    @Test
    @DisplayName("GET /upsert/groups/{groupId}/balances: returns group balances")
    void getBalances_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        GroupBalanceResponse balance = new GroupBalanceResponse(1L, "Trip to Goa", new ArrayList<>(), new ArrayList<>());

        when(splitService.getGroupBalances(1L)).thenReturn(balance);

        mockMvc.perform(get("/upsert/groups/1/balances")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk());
    }
}
