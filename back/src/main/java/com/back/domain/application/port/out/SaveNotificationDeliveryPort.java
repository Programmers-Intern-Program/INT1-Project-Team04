package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationDelivery;

public interface SaveNotificationDeliveryPort {
    NotificationDelivery save(NotificationDelivery delivery);
}
