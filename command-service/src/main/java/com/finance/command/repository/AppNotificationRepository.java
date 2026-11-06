package com.finance.command.repository;

import com.finance.command.model.AppNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {

    Page<AppNotification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query("update AppNotification n set n.readAt = :readAt " +
           "where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);

    @Modifying
    @Query("update AppNotification n set n.readAt = :readAt " +
           "where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") Long id, @Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);
}
