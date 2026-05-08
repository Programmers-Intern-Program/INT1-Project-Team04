package com.back.domain.adapter.in.web.notification;

public record TelegramWebhookRequest(
        Message message
) {
    public record Message(
            String text,
            Chat chat
    ) {
    }

    public record Chat(
            Long id
    ) {
    }
}
