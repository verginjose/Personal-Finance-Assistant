package com.finance.command.dto;

import com.finance.command.model.AppNotification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationView(
            Long id,
            String status,
            String event,
            String message,
            Long groupId,
            String payload,
            LocalDateTime createdAt,
            LocalDateTime readAt
    ) {
        public static NotificationView from(AppNotification n) {
            return new NotificationView(n.getId(), n.getStatus(), n.getEvent(), n.getMessage(),
                    n.getGroupId(), n.getPayload(), n.getCreatedAt(), n.getReadAt());
        }
    }

    public record NotificationPage(
            List<NotificationView> items,
            long unread,
            int page,
            int totalPages
    ) {}

    public record UnreadCount(long unread) {}
}
