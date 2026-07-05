package com.finance.command.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", schema = "finance", indexes = {
    @Index(name = "idx_subscription_user", columnList = "user_id, active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringPeriod period;

    /** Expected date of the next charge, calculated from last detected transaction. */
    @Column(name = "next_charge_date")
    private LocalDate nextChargeDate;

    /** Days remaining until next charge (computed on load). */
    @Column(name = "days_until_charge")
    private int daysUntilCharge;

    /** Whether this subscription is still considered active. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        detectedAt = LocalDateTime.now();
        lastSeenAt = LocalDateTime.now();
    }
}
