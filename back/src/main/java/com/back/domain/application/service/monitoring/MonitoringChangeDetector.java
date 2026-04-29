package com.back.domain.application.service.monitoring;

import com.back.domain.application.service.subscriptionconversation.StructuredCondition;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MonitoringChangeDetector {

    private static final List<String> AVG_PRICE_KEYS = List.of(
            "avg_deal_amount",
            "avg_deposit",
            "avg_monthly_rent",
            "count"
    );

    public MonitoringChangeDecision detect(
            JsonNode previousSummary,
            JsonNode currentSummary,
            Map<String, String> parameters
    ) {
        return StructuredCondition.fromParameters(parameters)
                .map(condition -> detect(previousSummary, currentSummary, condition))
                .orElseGet(() -> MonitoringChangeDecision.notTriggered("condition missing"));
    }

    private MonitoringChangeDecision detect(
            JsonNode previousSummary,
            JsonNode currentSummary,
            StructuredCondition condition
    ) {
        String metricKey = metricKey(previousSummary, currentSummary, condition);
        if (metricKey == null) {
            return MonitoringChangeDecision.notTriggered("metric missing");
        }

        BigDecimal previous = decimal(previousSummary.path(metricKey));
        BigDecimal current = decimal(currentSummary.path(metricKey));
        if (previous == null || current == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return MonitoringChangeDecision.notTriggered("metric not comparable");
        }

        BigDecimal change = current.subtract(previous);
        if (!matchesDirection(change, condition.direction())) {
            return MonitoringChangeDecision.notTriggered("direction mismatch");
        }

        BigDecimal rate = change
                .multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        BigDecimal comparable = comparableValue(condition, change, rate);
        BigDecimal threshold = threshold(condition);
        if (!matchesOperator(comparable, threshold, condition.operator())) {
            return MonitoringChangeDecision.notTriggered("threshold not reached");
        }
        return MonitoringChangeDecision.triggered(metricKey, previous, current, change, rate);
    }

    private String metricKey(JsonNode previousSummary, JsonNode currentSummary, StructuredCondition condition) {
        if (condition.metric() != StructuredCondition.Metric.AVG_PRICE) {
            return null;
        }
        return AVG_PRICE_KEYS.stream()
                .filter(key -> decimal(previousSummary.path(key)) != null)
                .filter(key -> decimal(currentSummary.path(key)) != null)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal comparableValue(StructuredCondition condition, BigDecimal change, BigDecimal rate) {
        if (condition.unit() == StructuredCondition.Unit.PERCENT) {
            return rate.abs();
        }
        return change.abs();
    }

    private BigDecimal threshold(StructuredCondition condition) {
        if (condition.unit() == StructuredCondition.Unit.EOK) {
            return condition.threshold().multiply(BigDecimal.valueOf(10_000));
        }
        return condition.threshold();
    }

    private boolean matchesDirection(BigDecimal change, StructuredCondition.Direction direction) {
        return switch (direction) {
            case UP -> change.compareTo(BigDecimal.ZERO) > 0;
            case DOWN -> change.compareTo(BigDecimal.ZERO) < 0;
            case ANY -> change.compareTo(BigDecimal.ZERO) != 0;
        };
    }

    private boolean matchesOperator(BigDecimal comparable, BigDecimal threshold, StructuredCondition.Operator operator) {
        int compared = comparable.compareTo(threshold);
        return switch (operator) {
            case GTE -> compared >= 0;
            case GT -> compared > 0;
            case LTE -> compared <= 0;
            case LT -> compared < 0;
        };
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.decimalValue();
    }
}
