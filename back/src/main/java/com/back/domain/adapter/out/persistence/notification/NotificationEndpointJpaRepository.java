package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationChannel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationEndpointJpaRepository extends JpaRepository<NotificationEndpointJpaEntity, String> {
    Optional<NotificationEndpointJpaEntity> findByUserIdAndChannelAndEnabledTrue(
            Long userId,
            NotificationChannel channel
    );
}
