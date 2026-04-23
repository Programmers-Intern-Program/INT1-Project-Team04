package com.back.domain.adapter.out.persistence.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceJpaRepository extends JpaRepository<NotificationPreferenceJpaEntity, String> {
    List<NotificationPreferenceJpaEntity> findBySubscriptionIdAndEnabledTrue(String subscriptionId);
}
