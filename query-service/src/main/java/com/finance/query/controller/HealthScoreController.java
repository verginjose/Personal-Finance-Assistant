package com.finance.query.controller;

import com.finance.query.dto.HealthScoreResponse;
import com.finance.query.service.HealthScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class HealthScoreController {

    private final HealthScoreService healthScoreService;

    @GetMapping("/health-score")
    public HealthScoreResponse getHealthScore(@RequestHeader(value = "X-User-Id", required = false) UUID userIdHeader, @RequestParam UUID userId) {
        if (userIdHeader != null && !userIdHeader.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User ID mismatch");
        }
        return healthScoreService.calculateScore(userId);
    }
}
