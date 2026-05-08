package com.back.domain.adapter.in.web.subscription;

import com.back.domain.model.notification.NotificationChannel;
import java.time.LocalDateTime;

public record SubscriptionSummaryResponse(
        String id,
        String query,
        String domainLabel,
        String cadenceLabel,
        NotificationChannel notificationChannel,
        String channelLabel,
        LocalDateTime nextRun,
        boolean active
) {
}
