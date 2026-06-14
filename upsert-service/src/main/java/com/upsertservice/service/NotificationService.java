package com.upsertservice.service;

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
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));
        emitter.onError((e) -> emitters.remove(userId));

        try {
            // Send initial ping to keep connection alive
            emitter.send(SseEmitter.event().name("ping").data("connected"));
        } catch (IOException e) {
            emitters.remove(userId);
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
            } catch (IOException e) {
                log.error("Failed to send SSE notification to user {}", userId, e);
                emitters.remove(userId);
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
            } catch (IOException e) {
                emitters.remove(entry.getKey());
            }
        }
    }
}
