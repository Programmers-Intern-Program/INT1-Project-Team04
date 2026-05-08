package com.back.domain.application.command;

import com.back.domain.model.notification.NotificationChannel;

public record CreateSubscriptionCommand(
        Long domainId,
        String query,
        String cronExpr,
        NotificationChannel notificationChannel,
        String notificationTargetAddress
) {
    public CreateSubscriptionCommand(Long domainId, String query, String cronExpr) {
        this(domainId, query, cronExpr, null, null);
    }
}
