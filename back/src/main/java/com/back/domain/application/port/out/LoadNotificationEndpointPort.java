package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import java.util.Optional;

public interface LoadNotificationEndpointPort {
    Optional<NotificationEndpoint> loadEnabledByUserIdAndChannel(Long userId, NotificationChannel channel);
}
