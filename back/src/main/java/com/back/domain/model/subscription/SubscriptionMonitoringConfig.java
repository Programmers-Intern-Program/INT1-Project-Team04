package com.back.domain.model.subscription;

public record SubscriptionMonitoringConfig(
        String subscriptionId,
        String toolName,
        String intent,
        String parametersJson
) {
}
