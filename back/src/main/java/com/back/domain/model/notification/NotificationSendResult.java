package com.back.domain.model.notification;

public record NotificationSendResult(
        boolean successful,
        boolean retryable,
        String providerMessageId,
        String failureReason
) {
    public static NotificationSendResult success(String providerMessageId) {
        return new NotificationSendResult(true, false, providerMessageId, null);
    }

    public static NotificationSendResult retryableFailure(String failureReason) {
        return new NotificationSendResult(false, true, null, failureReason);
    }

    public static NotificationSendResult permanentFailure(String failureReason) {
        return new NotificationSendResult(false, false, null, failureReason);
    }
}
