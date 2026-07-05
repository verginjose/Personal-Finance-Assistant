package com.finance.command.controller;

import com.finance.command.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal HTTP endpoints called by other services directly (replaces Kafka consumers).
 * These endpoints are NOT exposed through the API Gateway.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final NotificationService notificationService;

    /**
     * Called by the ocr-service (bill-parser) when bill processing completes.
     * Sends an SSE notification to the connected browser client.
     *
     * Expected body: { "userId": "...", "status": "SUCCESS|ERROR", "data": {...}, "message": "..." }
     */
    @PostMapping("/ocr-complete")
    public ResponseEntity<Void> ocrComplete(@RequestBody Map<String, Object> event) {
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
}
