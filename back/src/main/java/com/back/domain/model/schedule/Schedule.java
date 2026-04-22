package com.back.domain.model.schedule;

import com.back.domain.model.subscription.Subscription;
import java.time.LocalDateTime;

public record Schedule(
        String id,
        Subscription subscription,
        String cronExpr,
        LocalDateTime lastRun,
        LocalDateTime nextRun
) {
}
