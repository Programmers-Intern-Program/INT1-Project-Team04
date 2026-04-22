package com.back.domain.application.port.out;

import com.back.domain.model.notification.Notification;

public interface SendNotificationPort {

    boolean send(Notification notification);
}
