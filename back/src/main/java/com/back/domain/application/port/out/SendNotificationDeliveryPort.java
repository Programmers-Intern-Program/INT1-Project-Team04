package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationSendResult;

public interface SendNotificationDeliveryPort {
    boolean supports(NotificationChannel channel);

    NotificationSendResult send(NotificationDelivery delivery);
}
