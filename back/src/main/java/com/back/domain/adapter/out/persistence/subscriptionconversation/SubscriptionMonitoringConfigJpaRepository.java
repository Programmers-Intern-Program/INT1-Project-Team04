package com.back.domain.adapter.out.persistence.subscriptionconversation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SubscriptionMonitoringConfigJpaRepository
        extends JpaRepository<SubscriptionMonitoringConfigJpaEntity, String> {
    Optional<SubscriptionMonitoringConfigJpaEntity> findBySubscriptionId(String subscriptionId);
}
