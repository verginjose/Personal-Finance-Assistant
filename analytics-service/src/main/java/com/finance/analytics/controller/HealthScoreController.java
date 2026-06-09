package com.finance.analytics.controller;

import com.finance.analytics.dto.HealthScoreResponse;
import com.finance.analytics.service.HealthScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@Tag(name = "Health Score", description = "Financial health score and grade")
public class HealthScoreController {

    private final HealthScoreService service;

    @GetMapping("/health-score")
    @Operation(summary = "Calculate the user's financial health score (0–1000)")
    public ResponseEntity<HealthScoreResponse> getHealthScore(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(service.calculateScore(userId));
    }
}
