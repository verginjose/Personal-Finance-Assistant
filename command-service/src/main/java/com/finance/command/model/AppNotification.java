package com.finance.command.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A notification addressed to one user. Rows are written for every event, so
 * notifications that arrived while the user was offline are still available
 * when they next open the app (the SSE stream only reaches live connections).
 */
@Entity
@Table(name = "notifications", schema = "finance", indexes = {
        @Index(name = "idx_notifications_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_notifications_user_unread", columnList = "user_id,read_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** INFO | SUCCESS | ERROR — mirrors the SSE payload's status field. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "INFO";

    @Column(length = 60)
    private String event;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "group_id")
    private Long groupId;

    /** Original event payload as JSON, so clients can reconstruct the event. */
    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
