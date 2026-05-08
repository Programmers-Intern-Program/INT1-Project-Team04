package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationEndpointPersistenceAdapter implements LoadNotificationEndpointPort, SaveNotificationEndpointPort {

    private final NotificationEndpointJpaRepository repository;

    @Override
    @Transactional
    public NotificationEndpoint save(NotificationEndpoint endpoint) {
        return repository.save(NotificationEndpointJpaEntity.from(endpoint)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationEndpoint> loadEnabledByUserIdAndChannel(Long userId, NotificationChannel channel) {
        return repository.findByUserIdAndChannelAndEnabledTrue(userId, channel)
                .map(NotificationEndpointJpaEntity::toDomain);
    }
}
