package com.finance.analytics.controller;

import com.finance.analytics.dto.AiInsightResponse;
import com.finance.analytics.dto.ChartData;
import com.finance.analytics.dto.HealthScoreResponse;
import com.finance.analytics.service.AiInsightsService;
import com.finance.analytics.service.AnalyticsService;
import com.finance.analytics.service.HealthScoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = {AnalyticsController.class, HealthScoreController.class, AiInsightsController.class},
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@DisplayName("Analytics controllers — Unit Tests")
public class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private HealthScoreService healthScoreService;

    @MockitoBean
    private AiInsightsService aiInsightsService;

    @MockitoBean
    private com.finance.analytics.service.GoalForecastingService goalForecastingService;

    @MockitoBean
    private com.finance.analytics.service.BudgetTrendService budgetTrendService;

    @Test
    @DisplayName("GET /analytics/category-pie-chart: returns chart data")
    void getCategoryPieChart_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        ChartData.DataSet dataset = new ChartData.DataSet("Expenses", List.of(200.0, 50.0), List.of("#FF0000", "#00FF00"));
        ChartData chartData = new ChartData("pie", "Category breakdown", List.of("Food", "Utilities"), List.of(dataset));

        when(analyticsService.getCategoryAnalytics(any())).thenReturn(chartData);

        mockMvc.perform(get("/analytics/category-pie-chart")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chartType").value("pie"))
                .andExpect(jsonPath("$.title").value("Category breakdown"))
                .andExpect(jsonPath("$.labels[0]").value("Food"));
    }

    @Test
    @DisplayName("GET /analytics/comprehensive: returns key-value analytics map")
    void getComprehensiveAnalytics_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        Map<String, Object> result = Map.of("totalSpent", 1250.0, "totalSaved", 300.0);

        when(analyticsService.getComprehensiveAnalytics(any())).thenReturn(result);

        mockMvc.perform(get("/analytics/comprehensive")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpent").value(1250.0))
                .andExpect(jsonPath("$.totalSaved").value(300.0));
    }

    @Test
    @DisplayName("GET /analytics/health-score: returns score & status 200 with X-User-Id header matching userId")
    void getHealthScore_matchingHeader_returnsScore() throws Exception {
        UUID userId = UUID.randomUUID();
        HealthScoreResponse response = new HealthScoreResponse(750, "B", Map.of("Savings", 80), "Good financial health", "2026-06-09T10:00:00");

        when(healthScoreService.calculateScore(userId)).thenReturn(response);

        mockMvc.perform(get("/analytics/health-score")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalScore").value(750))
                .andExpect(jsonPath("$.grade").value("B"));
    }

    @Test
    @DisplayName("GET /analytics/health-score: returns status 403 when X-User-Id header is mismatched")
    void getHealthScore_mismatchedHeader_returns403() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID differentId = UUID.randomUUID();

        mockMvc.perform(get("/analytics/health-score")
                        .header("X-User-Id", differentId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /analytics/ai-insights: returns insights when X-User-Id matches")
    void getAiInsights_matchingHeader_returnsInsights() throws Exception {
        UUID userId = UUID.randomUUID();
        AiInsightResponse.Insight insight = new AiInsightResponse.Insight("Netflix cost", "Watch out for Netflix cost.", "WARNING", 2);
        AiInsightResponse response = new AiInsightResponse(List.of(insight), "Summary text", "2026-06-09T10:00:00");

        when(aiInsightsService.generateInsights(userId)).thenReturn(response);

        mockMvc.perform(get("/analytics/ai-insights")
                        .header("X-User-Id", userId.toString())
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Summary text"))
                .andExpect(jsonPath("$.insights[0].title").value("Netflix cost"));
    }
}
