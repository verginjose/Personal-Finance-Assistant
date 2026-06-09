package com.authservice.controller;

import com.authservice.config.SecurityConfig;
import com.authservice.dto.AuthDtos.*;
import com.authservice.filter.JwtAuthenticationFilter;
import com.authservice.model.Role;
import com.authservice.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = AuthController.class, 
    excludeAutoConfiguration = SecurityAutoConfiguration.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {SecurityConfig.class, JwtAuthenticationFilter.class}
    )
)
@DisplayName("AuthController — Unit Tests")
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("POST /auth/login: returns status 200 and login response on success")
    void login_succeeds_returns200() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "password");
        LoginResponse response = new LoginResponse("token", "refresh", "user-id", "test@example.com", "USER");

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token"))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @DisplayName("POST /auth/register: returns status 201 and register response on success")
    void register_succeeds_returns201() throws Exception {
        RegisterRequest request = new RegisterRequest("new@example.com","summa", "password", Role.USER);
        RegisterResponse response = new RegisterResponse("user-id", "new@example.com", "summa","USER");
        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("POST /auth/refresh: returns new tokens")
    void refresh_succeeds_returnsTokens() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("old-refresh-token");
        LoginResponse response = new LoginResponse("new-token", "new-refresh", "user-id", "test@example.com", "USER");

        when(authService.refresh(any(RefreshTokenRequest.class))).thenReturn(response);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-token"));
    }

    @Test
    @DisplayName("GET /auth/health: returns UP status")
    void health_returns200() throws Exception {
        mockMvc.perform(get("/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
