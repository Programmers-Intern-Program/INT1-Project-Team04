package com.back.domain.application.result;

import com.back.domain.model.notification.NotificationChannel;

public record NotificationEndpointConnectionResult(
        NotificationChannel channel,
        boolean connected,
        String targetLabel,
        String connectUrl,
        String authorizationUrl,
        String message
) {
}
