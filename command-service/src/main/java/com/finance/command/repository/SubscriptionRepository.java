package com.finance.command.repository;

import com.finance.command.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserIdAndActiveTrueOrderByDaysUntilChargeAsc(UUID userId);

    Optional<Subscription> findByUserIdAndNameAndAmount(UUID userId, String name, java.math.BigDecimal amount);

    /**
     * Match on the merchant name alone so a price change updates the existing
     * subscription instead of adding a duplicate a few rupees apart.
     */
    Optional<Subscription> findFirstByUserIdAndNameIgnoreCaseOrderByIdAsc(UUID userId, String name);

    List<Subscription> findByUserId(UUID userId);
}
