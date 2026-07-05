package com.finance.query.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "group_members", schema = "groups",
       uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMember {

    public enum InvitationStatus {
        PENDING, ACCEPTED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @JsonIgnore
    private ExpenseGroup group;

    @Column(name = "group_id", insertable = false, updatable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Display name or email for UI use */
    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "is_archived", nullable = false)
    private boolean isArchived = false;

    @PrePersist
    protected void onCreate() {
        joinedAt = LocalDateTime.now();
    }
}
