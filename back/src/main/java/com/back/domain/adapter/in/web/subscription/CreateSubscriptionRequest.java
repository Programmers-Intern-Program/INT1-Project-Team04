package com.back.domain.adapter.in.web.subscription;

import com.back.domain.model.notification.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSubscriptionRequest(
        @NotNull Long domainId,
        @NotBlank String query,
        @NotBlank String cronExpr,
        NotificationChannel notificationChannel,
        String notificationTargetAddress
) {
}
