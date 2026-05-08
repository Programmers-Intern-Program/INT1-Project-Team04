package com.back.domain.application.result;

import com.back.domain.model.notification.NotificationChannel;

public record NotificationEndpointStatusResult(
        NotificationChannel channel,
        boolean connected,
        String targetLabel
) {
}
