package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationChannel;

public interface LoadDuplicateSubscriptionPort {

    boolean existsActiveDuplicate(
            Long userId,
            Long domainId,
            String normalizedQuery,
            String cronExpr,
            NotificationChannel notificationChannel
    );
}
