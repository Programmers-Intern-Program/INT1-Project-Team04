package com.back.domain.application.port.out;

import com.back.domain.model.notification.NotificationPreference;
import java.util.List;

public interface LoadEnabledNotificationPreferencePort {
    List<NotificationPreference> loadEnabledBySubscriptionId(String subscriptionId);
}
