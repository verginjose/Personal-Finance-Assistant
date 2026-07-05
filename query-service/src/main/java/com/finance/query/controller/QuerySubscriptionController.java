package com.finance.query.controller;

import com.finance.query.dto.SubscriptionResponse;
import com.finance.query.service.SubscriptionDetectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/upsert/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscription Detector", description = "Detect and manage recurring \"vampire\" expenses")
public class QuerySubscriptionController {

    private final SubscriptionDetectorService service;

    @GetMapping
    @Operation(summary = "Get detected active subscriptions for user")
    public ResponseEntity<List<SubscriptionResponse>> getSubscriptions(
            @RequestHeader("X-User-Id") String xUserId,
            @RequestParam UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            throw new SecurityException("User ID mismatch");
        }
        return ResponseEntity.ok(service.getSubscriptionsForUser(userId));
    }
}
