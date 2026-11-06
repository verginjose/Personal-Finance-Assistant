package com.finance.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.command.repository.AppNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationService — live connections and inbox")
class NotificationServiceTest {

    @Mock AppNotificationRepository notificationRepo;

    private NotificationService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepo, new ObjectMapper());
    }

    @Test
    @DisplayName("every device signed in as the same user receives the notification")
    void broadcastsToEveryConnection() {
        RecordingEmitter phone = new RecordingEmitter();
        RecordingEmitter laptop = new RecordingEmitter();
        service.register(userId, phone);
        service.register(userId, laptop);

        service.sendNotification(userId, Map.of("status", "SUCCESS", "message", "OCR done"));

        assertThat(phone.events).anyMatch(e -> e.contains("OCR done"));
        assertThat(laptop.events).anyMatch(e -> e.contains("OCR done"));
    }

    @Test
    @DisplayName("one device disconnecting leaves the other subscribed")
    void detachRemovesOnlyThatConnection() {
        RecordingEmitter phone = new RecordingEmitter();
        RecordingEmitter laptop = new RecordingEmitter();
        service.register(userId, phone);
        service.register(userId, laptop);

        service.detach(userId, phone);
        service.sendNotification(userId, Map.of("status", "INFO", "message", "second"));

        assertThat(phone.events).noneMatch(e -> e.contains("second"));
        assertThat(laptop.events).anyMatch(e -> e.contains("second"));
    }

    @Test
    @DisplayName("clearAll deletes only the caller's notifications")
    void clearAllScopesToUser() {
        when(notificationRepo.deleteAllByUserId(userId)).thenReturn(4);

        assertThat(service.clearAll(userId)).isEqualTo(4);
        verify(notificationRepo).deleteAllByUserId(userId);
    }

    /** Captures what the service would have written to the wire. */
    private static final class RecordingEmitter extends SseEmitter {
        final List<String> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder out = new StringBuilder();
            // Non-String payloads stay objects here; the converter serializes them
            // later, so record their toString() as a stand-in for the wire body.
            builder.build().forEach(part -> out.append(String.valueOf(part.getData())));
            events.add(out.toString());
        }
    }
}
