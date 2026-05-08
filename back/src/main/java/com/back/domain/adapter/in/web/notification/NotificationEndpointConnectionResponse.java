package com.back.domain.adapter.in.web.notification;

import com.back.domain.application.result.NotificationEndpointConnectionResult;
import com.back.domain.model.notification.NotificationChannel;

public record NotificationEndpointConnectionResponse(
        NotificationChannel channel,
        boolean connected,
        String targetLabel,
        String connectUrl,
        String authorizationUrl,
        String message
) {
    public static NotificationEndpointConnectionResponse from(NotificationEndpointConnectionResult result) {
        return new NotificationEndpointConnectionResponse(
                result.channel(),
                result.connected(),
                result.targetLabel(),
                result.connectUrl(),
                result.authorizationUrl(),
                result.message()
        );
    }
}
