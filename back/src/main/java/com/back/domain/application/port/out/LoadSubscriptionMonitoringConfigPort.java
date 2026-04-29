package com.back.domain.application.port.out;

import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import java.util.Optional;

public interface LoadSubscriptionMonitoringConfigPort {
    Optional<SubscriptionMonitoringConfig> loadBySubscriptionId(String subscriptionId);
}
