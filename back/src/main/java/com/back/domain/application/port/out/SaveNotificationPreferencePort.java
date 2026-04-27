package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationPreference;

public interface SaveNotificationPreferencePort {
    NotificationPreference save(NotificationPreference preference);
}
