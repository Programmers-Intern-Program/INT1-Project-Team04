package com.back.domain.model.notification;

public record NotificationPreference(
        String id,
        String subscriptionId,
        NotificationChannel channel,
        boolean enabled
) {
}
