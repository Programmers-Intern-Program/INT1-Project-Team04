package com.back.domain.model.notification;

import com.back.domain.model.subscription.Subscription;
import java.time.LocalDateTime;

public record AlertEvent(
        String id,
        Subscription subscription,
        String title,
        String summary,
        String reason,
        String sourceUrl,
        AlertSeverity severity,
        LocalDateTime createdAt
) {
}
