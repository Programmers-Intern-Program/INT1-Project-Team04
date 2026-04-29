package com.back.domain.adapter.out.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

record MonitoringBriefingResponse(
        String title,
        String summary,
        List<String> keyChanges,
        List<String> watchPoints
) {

    private static final Set<String> FIELDS = Set.of("title", "summary", "keyChanges", "watchPoints");

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
                    title.get(),
                    summary.get(),
                    keyChanges.get(),
                    watchPoints.get()
            ));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    String toMessage() {
        StringBuilder message = new StringBuilder("[AI 변화 브리핑] ").append(title);
        message.append("\n\n").append(summary);
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
        values.forEach(value -> message.append("\n- ").append(value));
    }
}
