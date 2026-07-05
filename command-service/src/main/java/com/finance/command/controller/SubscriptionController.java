package com.finance.command.controller;

import com.finance.command.dto.SubscriptionResponse;
import com.finance.command.service.SubscriptionDetectorService;
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
public class SubscriptionController {

    private final SubscriptionDetectorService service;



    @DeleteMapping("/{id}/deactivate")
    @Operation(summary = "Mark a subscription as inactive (dismiss alert)")
    public ResponseEntity<Void> deactivate(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long id,
            @RequestParam UUID userId) {
        validateUser(xUserId, userId);
        service.deactivateSubscription(id, userId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update subscription details")
    public ResponseEntity<SubscriptionResponse> update(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long id,
            @RequestParam UUID userId,
            @jakarta.validation.Valid @RequestBody com.finance.command.dto.UpdateSubscriptionRequest request) {
        validateUser(xUserId, userId);
        return ResponseEntity.ok(service.updateSubscription(id, userId, request));
    }

    private void validateUser(String xUserId, UUID userId) {
        if (!UUID.fromString(xUserId).equals(userId)) {
            throw new SecurityException("User ID mismatch");
        }
    }
}
