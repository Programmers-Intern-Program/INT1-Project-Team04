package com.back.domain.application.service.monitoring;

import java.math.BigDecimal;

public record MonitoringChangeDecision(
        boolean triggered,
        String metricKey,
        BigDecimal previousValue,
        BigDecimal currentValue,
        BigDecimal changeValue,
        BigDecimal changeRate,
        String reason
) {

    public static MonitoringChangeDecision triggered(
            String metricKey,
            BigDecimal previousValue,
            BigDecimal currentValue,
            BigDecimal changeValue,
            BigDecimal changeRate
    ) {
        return new MonitoringChangeDecision(true, metricKey, previousValue, currentValue, changeValue, changeRate, "condition matched");
    }

    public static MonitoringChangeDecision notTriggered(String reason) {
        return new MonitoringChangeDecision(false, null, null, null, null, null, reason);
    }
}
