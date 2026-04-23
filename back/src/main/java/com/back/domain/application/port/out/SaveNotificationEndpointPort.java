package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationEndpoint;

public interface SaveNotificationEndpointPort {
    NotificationEndpoint save(NotificationEndpoint endpoint);
}
