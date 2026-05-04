package com.back.domain.application.service.monitoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class MonitoringAlertMessageBuilder {

    public String build(String subscriptionQuery, String toolName, MonitoringChangeDecision decision, String mcpContent) {
        StringBuilder message = new StringBuilder();
        message.append("[변화 감지] ").append(subscriptionQuery).append('\n');
        message.append("- 도구: ").append(toolName).append('\n');
        message.append("- 지표: ").append(decision.metricKey()).append('\n');
        message.append("- 이전: ").append(formatNumber(decision.previousValue())).append('\n');
        message.append("- 현재: ").append(formatNumber(decision.currentValue())).append('\n');
        message.append("- 변화: ").append(formatNumber(decision.changeValue()))
                .append(" (").append(formatPercent(decision.changeRate())).append(")");
        if (mcpContent != null && !mcpContent.isBlank()) {
            message.append("\n\n").append(mcpContent);
        }
        return message.toString();
    }

    private String formatPercent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private String formatNumber(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
