package com.finance.analytics.controller;

import com.finance.analytics.dto.AiInsightResponse;
import com.finance.analytics.service.AiInsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@Tag(name = "AI Insights", description = "Proactive AI-powered financial advisor")
public class AiInsightsController {

    private final AiInsightsService service;

    @GetMapping("/ai-insights")
    @Operation(summary = "Generate personalized AI financial insights for the current month")
    public ResponseEntity<AiInsightResponse> getInsights(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(service.generateInsights(userId));
    }
}
