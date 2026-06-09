package com.upsertservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "group_activities", schema = "activity", indexes = {
        @Index(name = "idx_group_activity_group_created", columnList = "group_id, created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "actor_name", nullable = false, length = 150)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 40)
    private ActivityType activityType;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ActivityType {
        GROUP_CREATED,
        MEMBER_ADDED,
        EXPENSE_ADDED,
        EXPENSE_DELETED,
        SETTLEMENT_RECORDED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
