package com.authservice.controller;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.Role;
import com.authservice.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("AuthService — Integration Tests with Testcontainers")
public class AuthIntegrationTest {

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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "auth");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clearDatabase() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("End-to-End Registration, Login, Profile and Logout flow")
    void e2eAuthFlow() throws Exception {
        // 1. Register a new user
        RegisterRequest registerRequest = new RegisterRequest("integration@example.com", "Password123!", Role.USER);
        
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("integration@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        // Verify database entry
        assertThat(userRepository.findByEmail("integration@example.com")).isPresent();

        // 2. Login with valid credentials
        LoginRequest loginRequest = new LoginRequest("integration@example.com", "Password123!");
        
        String loginResponseStr = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.email").value("integration@example.com"))
                .andReturn().getResponse().getContentAsString();

        LoginResponse loginResponse = objectMapper.readValue(loginResponseStr, LoginResponse.class);

        // 3. Get currently authenticated user profile
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + loginResponse.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("integration@example.com"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        // 4. Refresh token
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(loginResponse.refreshToken());
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 5. Logout
        LogoutRequest logoutRequest = new LogoutRequest(loginResponse.refreshToken());
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + loginResponse.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    @Test
    @DisplayName("GET /auth/health endpoint returns UP status")
    void healthCheck() throws Exception {
        mockMvc.perform(get("/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
