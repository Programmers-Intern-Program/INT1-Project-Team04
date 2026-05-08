package com.back.domain.adapter.in.web.notification;

import com.back.domain.application.result.NotificationEndpointStatusResult;
import com.back.domain.model.notification.NotificationChannel;

public record NotificationEndpointStatusResponse(
        NotificationChannel channel,
        boolean connected,
        String targetLabel
) {
    public static NotificationEndpointStatusResponse from(NotificationEndpointStatusResult result) {
        return new NotificationEndpointStatusResponse(
                result.channel(),
                result.connected(),
                result.targetLabel()
        );
    }
}
