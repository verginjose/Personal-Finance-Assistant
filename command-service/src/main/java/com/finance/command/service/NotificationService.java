package com.finance.command.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class NotificationService {

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

    @Async
    public void sendNotification(UUID userId, Object payload) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(payload));
                log.info("Sent SSE notification to user {}", userId);
            } catch (IOException | IllegalStateException e) {
                log.warn("Failed to send SSE notification to user {}: {}", userId, e.getMessage());
                emitters.remove(userId, emitter);
            }
        } else {
            log.warn("No active SSE connection for user {}", userId);
        }
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
