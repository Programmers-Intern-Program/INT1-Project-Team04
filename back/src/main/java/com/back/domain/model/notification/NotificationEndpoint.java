package com.back.domain.model.notification;

public record NotificationEndpoint(
        String id,
        Long userId,
        NotificationChannel channel,
        String targetAddress,
        boolean enabled
) {
}
