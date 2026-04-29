package com.back.domain.application.service.subscriptionconversation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record StructuredCondition(
        Metric metric,
        Direction direction,
        Operator operator,
        BigDecimal threshold,
        Unit unit
) {
    private static final Pattern THRESHOLD = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(%|퍼센트|프로|만원|억)?");

    public StructuredCondition {
        if (metric == null || direction == null || operator == null || threshold == null || unit == null) {
            throw new IllegalArgumentException("condition fields must not be null");
        }
    }

    public static Optional<StructuredCondition> parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = THRESHOLD.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }

        BigDecimal threshold = new BigDecimal(matcher.group(1)).stripTrailingZeros();
        Unit unit = Unit.from(matcher.group(2));
        Direction direction = Direction.from(text);
        Operator operator = Operator.from(text);
        return Optional.of(new StructuredCondition(
                Metric.AVG_PRICE,
                direction,
                operator,
                threshold,
                unit
        ));
    }

    public static Optional<StructuredCondition> fromParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Optional.empty();
        }
        try {
            String metric = parameters.get("conditionMetric");
            String direction = parameters.get("conditionDirection");
            String operator = parameters.get("conditionOperator");
            String threshold = parameters.get("conditionThreshold");
            String unit = parameters.get("conditionUnit");
            if (isBlank(metric) || isBlank(direction) || isBlank(operator) || isBlank(threshold) || isBlank(unit)) {
                return parse(parameters.get("condition"));
            }
            return Optional.of(new StructuredCondition(
                    Metric.valueOf(metric),
                    Direction.valueOf(direction),
                    Operator.valueOf(operator),
                    new BigDecimal(threshold).stripTrailingZeros(),
                    Unit.valueOf(unit)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Map<String, String> toParameterMap() {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("conditionMetric", metric.name());
        value.put("conditionDirection", direction.name());
        value.put("conditionOperator", operator.name());
        value.put("conditionThreshold", threshold.toPlainString());
        value.put("conditionUnit", unit.name());
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Metric {
        AVG_PRICE
    }

    public enum Direction {
        UP,
        DOWN,
        ANY;

        private static Direction from(String text) {
            if (text.contains("하락") || text.contains("떨어") || text.contains("내리")) {
                return DOWN;
            }
            if (text.contains("상승") || text.contains("오르") || text.contains("올라")) {
                return UP;
            }
            return ANY;
        }
    }

    public enum Operator {
        GTE,
        GT,
        LTE,
        LT;

        private static Operator from(String text) {
            if (text.contains("미만")) {
                return LT;
            }
            if (text.contains("이하")) {
                return LTE;
            }
            if (text.contains("초과")) {
                return GT;
            }
            return GTE;
        }
    }

    public enum Unit {
        PERCENT,
        MANWON,
        EOK;

        private static Unit from(String raw) {
            if ("만원".equals(raw)) {
                return MANWON;
            }
            if ("억".equals(raw)) {
                return EOK;
            }
            return PERCENT;
        }
    }
}
