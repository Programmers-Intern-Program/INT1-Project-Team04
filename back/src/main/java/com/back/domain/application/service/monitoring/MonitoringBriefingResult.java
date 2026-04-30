package com.back.domain.application.service.monitoring;

public record MonitoringBriefingResult(
        boolean notificationRecommended,
        String message
) {
}
