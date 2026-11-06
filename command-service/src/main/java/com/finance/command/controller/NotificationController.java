package com.finance.command.controller;

import com.finance.command.dto.NotificationDtos.NotificationPage;
import com.finance.command.dto.NotificationDtos.UnreadCount;
import com.finance.command.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(@RequestHeader("X-User-Id") UUID userId, jakarta.servlet.http.HttpServletResponse response) {
        log.info("SSE connection requested for user {}", userId);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        return notificationService.subscribe(userId);
    }

    /* ─── INBOX (missed notifications) ─── */

    @GetMapping
    public ResponseEntity<NotificationPage> inbox(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(notificationService.inbox(userId, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCount> unreadCount(@RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(notificationService.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, String>> markRead(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable Long id) {
        notificationService.markRead(userId, id);
        return ResponseEntity.ok(Map.of("message", "Notification marked read"));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, String>> markAllRead(@RequestHeader("X-User-Id") UUID userId) {
        notificationService.markAllRead(userId);
        return ResponseEntity.ok(Map.of("message", "All notifications marked read"));
    }
}
