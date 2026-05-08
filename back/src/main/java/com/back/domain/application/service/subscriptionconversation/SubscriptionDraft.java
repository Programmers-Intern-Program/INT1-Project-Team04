package com.back.domain.application.service.subscriptionconversation;

import java.util.List;
import java.util.Map;

public record SubscriptionDraft(
        String query,
        String domainName,
        String intent,
        String toolName,
        Map<String, String> monitoringParams,
        String cronExpr,
        String notificationChannel,
        String notificationTargetAddress,
        List<String> missingFields,
        String assistantMessage,
        double confidence
) {
}
