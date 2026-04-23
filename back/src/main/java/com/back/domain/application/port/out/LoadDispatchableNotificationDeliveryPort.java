package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationDelivery;
import java.time.LocalDateTime;
import java.util.List;

public interface LoadDispatchableNotificationDeliveryPort {
    List<NotificationDelivery> loadDispatchable(LocalDateTime now);
}
