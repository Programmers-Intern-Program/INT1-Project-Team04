package com.back.domain.application.service.dev;

public record DevSubscriptionChangeSimulationResult(
        String subscriptionId,
        boolean triggered,
        boolean briefingGenerated,
        int deliveryCount,
        int dispatchedCount,
        String metricKey,
        String reason
) {
}
