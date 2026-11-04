package com.finance.query.controller;

import com.finance.query.dto.AiInsightResponse;
import com.finance.query.service.AiInsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AiInsightsController {

    private final AiInsightsService aiInsightsService;

    @GetMapping("/ai-insights")
    public AiInsightResponse getAiInsights(
            @RequestHeader(value = "X-User-Id", required = false) UUID userIdHeader,
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (userIdHeader != null && !userIdHeader.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User ID mismatch");
        }
        return refresh
                ? aiInsightsService.regenerateInsights(userId, startDate, endDate)
                : aiInsightsService.generateInsights(userId, startDate, endDate);
    }
}
