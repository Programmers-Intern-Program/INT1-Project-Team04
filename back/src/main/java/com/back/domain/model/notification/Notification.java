package com.back.domain.model.notification;

import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.user.User;
import java.time.LocalDateTime;

public record Notification(
        String id,
        Schedule schedule,
        User user,
        AiDataHub aiDataHub,
        String channel,
        String message,
        LocalDateTime sentAt,
        NotificationStatus status
) {
}
