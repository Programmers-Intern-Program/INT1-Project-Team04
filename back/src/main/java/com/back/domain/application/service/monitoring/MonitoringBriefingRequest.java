package com.back.domain.application.service.monitoring;

public record MonitoringBriefingRequest(
        String subscriptionQuery,
        String toolName,
        MonitoringChangeDecision decision,
        String previousSummaryJson,
        String currentSummaryJson,
        String mcpContent
) {
}
