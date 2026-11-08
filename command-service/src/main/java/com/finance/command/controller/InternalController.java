package com.finance.command.controller;

import com.finance.command.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * Internal HTTP endpoints called by other services directly (replaces Kafka consumers).
 * These endpoints are NOT exposed through the API Gateway.
 *
 * The body carries a {@code userId}, so an unauthenticated caller could push a
 * notification into any account. Every request must carry the shared
 * {@code X-Internal-Token}; NetworkPolicy restricts who can reach the port as
 * well. The endpoints fail closed when the token is not configured.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final NotificationService notificationService;

    @Value("${internal.api.token:}")
    private String internalApiToken;

    /**
     * @return null when the caller may proceed, otherwise the response to send.
     */
    private ResponseEntity<Void> rejectUnauthorized(String presented) {
        if (internalApiToken == null || internalApiToken.isBlank()) {
            log.error("INTERNAL_API_TOKEN is not configured; refusing /internal requests");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                internalApiToken.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected an /internal request with a missing or wrong token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return null;
    }

    /**
     * Called by the ocr-service (bill-parser) when bill processing completes.
     * Sends an SSE notification to the connected browser client.
     *
     * Expected body: { "userId": "...", "status": "SUCCESS|ERROR", "data": {...}, "message": "..." }
     */
    @PostMapping("/ocr-complete")
    public ResponseEntity<Void> ocrComplete(
            @RequestHeader(value = TOKEN_HEADER, required = false) String presented,
            @RequestBody Map<String, Object> event) {
        ResponseEntity<Void> denied = rejectUnauthorized(presented);
        if (denied != null) return denied;
        String userIdStr = (String) event.get("userId");
        if (userIdStr == null) {
            log.warn("Received ocr-complete event with no userId");
            return ResponseEntity.badRequest().build();
        }
        try {
            UUID userId = UUID.fromString(userIdStr);
            notificationService.sendNotification(userId, event);
            log.info("SSE notification sent for OCR completion, user={}, status={}", userId, event.get("status"));
        } catch (Exception e) {
            log.error("Failed to send OCR completion notification: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/batch-ocr-complete")
    public ResponseEntity<Void> batchOcrComplete(
            @RequestHeader(value = TOKEN_HEADER, required = false) String presented,
            @RequestBody Map<String, Object> event) {
        ResponseEntity<Void> denied = rejectUnauthorized(presented);
        if (denied != null) return denied;
        String userIdStr = (String) event.get("userId");
        if (userIdStr == null) {
            log.warn("Received batch-ocr-complete event with no userId");
            return ResponseEntity.badRequest().build();
        }
        try {
            UUID userId = UUID.fromString(userIdStr);
            event.put("type", "batch-ocr-completed"); // Differentiate event in UI
            notificationService.sendNotification(userId, event);
            log.info("SSE notification sent for Batch OCR completion, user={}, status={}", userId, event.get("status"));
        } catch (Exception e) {
            log.error("Failed to send Batch OCR completion notification: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
