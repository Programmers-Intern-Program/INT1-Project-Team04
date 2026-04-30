package com.back.domain.adapter.out.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record MonitoringBriefingResponse(
        boolean notificationRecommended,
        String title,
        String summary,
        List<String> keyChanges,
        List<String> watchPoints
) {

    private static final Pattern MONEY_AMOUNT = Pattern.compile("(?<![0-9.])([0-9]{4,})(?![0-9.%])");
    private static final Set<String> FIELDS = Set.of(
            "notificationRecommended",
            "title",
            "summary",
            "keyChanges",
            "watchPoints"
    );

    static Optional<MonitoringBriefingResponse> parse(ObjectMapper objectMapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (!root.isObject() || hasUnexpectedField(root)) {
                return Optional.empty();
            }
            Optional<String> title = requiredText(root, "title");
            Optional<String> summary = requiredText(root, "summary");
            Optional<List<String>> keyChanges = requiredTextArray(root, "keyChanges");
            Optional<List<String>> watchPoints = requiredTextArray(root, "watchPoints");
            if (title.isEmpty() || summary.isEmpty() || keyChanges.isEmpty() || watchPoints.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MonitoringBriefingResponse(
                    optionalBoolean(root, "notificationRecommended").orElse(true),
                    title.get(),
                    summary.get(),
                    keyChanges.get(),
                    watchPoints.get()
            ));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    String toMessage() {
        StringBuilder message = new StringBuilder("[AI 변화 브리핑] ").append(title);
        message.append("\n\n").append(formatMoneyAmounts(summary));
        appendSection(message, "핵심 변화", keyChanges);
        appendSection(message, "확인할 점", watchPoints);
        return message.toString();
    }

    private static boolean hasUnexpectedField(JsonNode root) {
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            if (!FIELDS.contains(fieldNames.next())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> requiredText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(node.asText());
    }

    private static Optional<Boolean> optionalBoolean(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null) {
            return Optional.empty();
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Field must be boolean: " + fieldName);
        }
        return Optional.of(node.asBoolean());
    }

    private static Optional<List<String>> requiredTextArray(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || !node.isArray()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return Optional.empty();
            }
            values.add(item.asText());
        }
        return Optional.of(values);
    }

    private static void appendSection(StringBuilder message, String title, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        message.append("\n\n").append(title).append(':');
        values.forEach(value -> message.append("\n- ").append(formatMoneyAmounts(value)));
    }

    private static String formatMoneyAmounts(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = MONEY_AMOUNT.matcher(value);
        StringBuilder formatted = new StringBuilder();
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            matcher.appendReplacement(formatted, Matcher.quoteReplacement(formatManwon(amount)));
        }
        matcher.appendTail(formatted);
        return formatted.toString();
    }

    private static String formatManwon(long amount) {
        if (amount >= 10_000) {
            long eok = amount / 10_000;
            long remainder = amount % 10_000;
            if (remainder == 0) {
                return eok + "억";
            }
            String decimal = String.valueOf(Math.round(remainder / 1000.0));
            if ("10".equals(decimal)) {
                return (eok + 1) + "억";
            }
            return eok + "." + decimal + "억";
        }
        return amount + "만원";
    }
}
