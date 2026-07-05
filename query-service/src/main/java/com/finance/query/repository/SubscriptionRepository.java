package com.finance.query.repository;

import com.finance.query.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserIdAndActiveTrueOrderByDaysUntilChargeAsc(UUID userId);

    Optional<Subscription> findByUserIdAndNameAndAmount(UUID userId, String name, java.math.BigDecimal amount);

    List<Subscription> findByUserId(UUID userId);
}
