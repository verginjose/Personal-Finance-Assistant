package com.finance.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.command.dto.NotificationDtos.NotificationPage;
import com.finance.command.dto.NotificationDtos.NotificationView;
import com.finance.command.dto.NotificationDtos.UnreadCount;
import com.finance.command.model.AppNotification;
import com.finance.command.repository.AppNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AppNotificationRepository notificationRepo;
    private final ObjectMapper objectMapper;

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        // Timeout 30 minutes
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        // Replacing an existing emitter for the same user (reconnect, second tab)
        // must not let the old emitter's callbacks remove its replacement.
        SseEmitter previous = emitters.put(userId, emitter);
        if (previous != null) {
            previous.complete();
        }

        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError((e) -> emitters.remove(userId, emitter));

        try {
            // Send initial ping to keep connection alive
            emitter.send(SseEmitter.event().name("ping").data("connected"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(userId, emitter);
        }

        return emitter;
    }

    /**
     * Persist the notification first, then push it to a live connection. The
     * row is what makes offline ("missed") notifications recoverable.
     */
    @Async
    public void sendNotification(UUID userId, Object payload) {
        AppNotification saved = null;
        try {
            saved = notificationRepo.save(toEntity(userId, payload));
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }

        // Include the inbox row id so a visible client can mark a notification
        // read while a hidden one leaves it unread for later.
        Object outbound = payload;
        if (saved != null && payload instanceof Map<?, ?> data) {
            Map<String, Object> copy = new HashMap<>();
            data.forEach((key, value) -> copy.put(String.valueOf(key), value));
            copy.put("notificationId", saved.getId());
            outbound = copy;
        }

        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(outbound));
                log.info("Sent SSE notification to user {}", userId);
            } catch (IOException | IllegalStateException e) {
                log.warn("Failed to send SSE notification to user {}: {}", userId, e.getMessage());
                emitters.remove(userId, emitter);
            }
        } else {
            log.info("No active SSE connection for user {} — notification stored for later", userId);
        }
    }

    private AppNotification toEntity(UUID userId, Object payload) {
        Map<?, ?> data = payload instanceof Map<?, ?> m ? m : Map.of();
        String status = stringOr(data.get("status"), "INFO");
        String eventName = data.get("event") != null ? String.valueOf(data.get("event")) : null;
        String message = stringOr(data.get("message"), String.valueOf(payload));
        Long groupId = longOrNull(data.get("groupId"));
        String json = null;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.debug("Could not serialize notification payload: {}", e.getMessage());
        }
        return AppNotification.builder()
                .userId(userId)
                .status(status)
                .event(eventName)
                .message(message)
                .groupId(groupId)
                .payload(json)
                .build();
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.valueOf(s); } catch (NumberFormatException ignored) { /* not an id */ }
        }
        return null;
    }

    /* ─── INBOX ─── */

    @Transactional(readOnly = true)
    public NotificationPage inbox(UUID userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        Page<AppNotification> result = notificationRepo.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(safePage, safeSize));
        return new NotificationPage(
                result.getContent().stream().map(NotificationView::from).toList(),
                notificationRepo.countByUserIdAndReadAtIsNull(userId),
                result.getNumber(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(UUID userId) {
        return new UnreadCount(notificationRepo.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional
    public void markRead(UUID userId, Long id) {
        notificationRepo.markRead(id, userId, LocalDateTime.now());
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepo.markAllRead(userId, LocalDateTime.now());
    }

    /** Keep the inbox bounded; notifications older than 30 days are dropped. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void pruneOldNotifications() {
        long removed = notificationRepo.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(30));
        if (removed > 0) log.info("Pruned {} notifications older than 30 days", removed);
    }

    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        for (Map.Entry<UUID, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().name("ping").data("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                // Remove only this emitter — a newer connection may have replaced it.
                emitters.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
