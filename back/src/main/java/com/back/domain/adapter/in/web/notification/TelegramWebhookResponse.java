package com.back.domain.adapter.in.web.notification;

public record TelegramWebhookResponse(
        boolean connected,
        String message
) {
}
