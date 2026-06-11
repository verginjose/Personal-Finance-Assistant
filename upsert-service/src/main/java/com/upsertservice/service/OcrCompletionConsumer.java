package com.upsertservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrCompletionConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ocr-completed", groupId = "upsert-service-group")
    public void consumeOcrCompletion(String message) {
        log.info("Received OCR completion event: {}", message);
        try {
            Map<String, Object> event = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});
            String userIdStr = (String) event.get("userId");
            if (userIdStr != null) {
                UUID userId = UUID.fromString(userIdStr);
                notificationService.sendNotification(userId, event);
            }
        } catch (Exception e) {
            log.error("Failed to parse OCR completion event", e);
        }
    }
}
