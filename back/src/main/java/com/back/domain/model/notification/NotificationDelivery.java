package com.back.domain.model.notification;

import java.time.LocalDateTime;

public record NotificationDelivery(
        String id,
        String alertEventId,
        String subscriptionId,
        Long userId,
        NotificationChannel channel,
        String recipient,
        String title,
        String message,
        NotificationDeliveryStatus status,
        int attemptCount,
        LocalDateTime nextRetryAt,
        LocalDateTime sentAt,
        String failureReason,
        String providerMessageId,
        LocalDateTime createdAt
) {
    public NotificationDelivery markSent(LocalDateTime sentAt, String providerMessageId) {
        return new NotificationDelivery(
                id,
                alertEventId,
                subscriptionId,
                userId,
                channel,
                recipient,
                title,
                message,
                NotificationDeliveryStatus.SENT,
                attemptCount + 1,
                null,
                sentAt,
                null,
                providerMessageId,
                createdAt
        );
    }

    public NotificationDelivery markRetry(LocalDateTime nextRetryAt, String failureReason) {
        return new NotificationDelivery(
                id,
                alertEventId,
                subscriptionId,
                userId,
                channel,
                recipient,
                title,
                message,
                NotificationDeliveryStatus.RETRY,
                attemptCount + 1,
                nextRetryAt,
                null,
                failureReason,
                providerMessageId,
                createdAt
        );
    }

    public NotificationDelivery markFailed(String failureReason) {
        return new NotificationDelivery(
                id,
                alertEventId,
                subscriptionId,
                userId,
                channel,
                recipient,
                title,
                message,
                NotificationDeliveryStatus.FAILED,
                attemptCount + 1,
                null,
                null,
                failureReason,
                providerMessageId,
                createdAt
        );
    }
}
