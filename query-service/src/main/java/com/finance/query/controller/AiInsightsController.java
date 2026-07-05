package com.finance.query.controller;

import com.finance.query.dto.AiInsightResponse;
import com.finance.query.service.AiInsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AiInsightsController {

    private final AiInsightsService aiInsightsService;

    @GetMapping("/ai-insights")
    public AiInsightResponse getAiInsights(@RequestHeader(value = "X-User-Id", required = false) UUID userIdHeader, @RequestParam UUID userId) {
        if (userIdHeader != null && !userIdHeader.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User ID mismatch");
        }
        return aiInsightsService.generateInsights(userId);
    }
}
