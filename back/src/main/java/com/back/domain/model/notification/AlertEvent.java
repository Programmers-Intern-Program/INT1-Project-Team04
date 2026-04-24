package com.back.domain.model.notification;

import com.back.domain.model.subscription.Subscription;
import java.time.LocalDateTime;
import java.util.List;

public record AlertEvent(
        String id,
        Subscription subscription,
        String title,
        String summary,
        String reason,
        List<AlertSource> sources,
        LocalDateTime createdAt
) {
}
