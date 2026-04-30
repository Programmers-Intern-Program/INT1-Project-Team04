package com.back.domain.adapter.out.ai;

import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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

    private static final Pattern MONEY_AMOUNT = Pattern.compile("(?<![0-9.])([0-9]{4,})(?![0-9.%원만억])");
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
            Optional<Boolean> notificationRecommended = optionalBoolean(root, "notificationRecommended");
            if (notificationRecommended.isEmpty()
                    || title.isEmpty()
                    || summary.isEmpty()
                    || keyChanges.isEmpty()
                    || watchPoints.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MonitoringBriefingResponse(
                    notificationRecommended.get(),
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
        return toMessage(null);
    }

    String toMessage(MonitoringChangeDecision decision) {
        StringBuilder message = new StringBuilder("[AI 변화 브리핑] ").append(title);
        message.append("\n\n").append(formatMoneyAmounts(summary, decision));
        appendSection(message, "핵심 변화", keyChanges, decision);
        appendSection(message, "확인할 점", watchPoints, decision);
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

    private static void appendSection(
            StringBuilder message,
            String title,
            List<String> values,
            MonitoringChangeDecision decision
    ) {
        if (values.isEmpty()) {
            return;
        }
        message.append("\n\n").append(title).append(':');
        values.forEach(value -> message.append("\n- ").append(formatMoneyAmounts(value, decision)));
    }

    private static String formatMoneyAmounts(String value, MonitoringChangeDecision decision) {
        String formatted = replaceDecisionAmounts(value, decision);
        return formatMoneyAmounts(formatted);
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

    private static String replaceDecisionAmounts(String value, MonitoringChangeDecision decision) {
        if (value == null || value.isBlank() || decision == null) {
            return value;
        }
        String formatted = value;
        formatted = replaceDecisionAmount(formatted, decision.previousValue());
        formatted = replaceDecisionAmount(formatted, decision.currentValue());
        formatted = replaceDecisionAmount(formatted, decision.changeValue());
        return formatted;
    }

    private static String replaceDecisionAmount(String value, BigDecimal amount) {
        if (value == null || amount == null) {
            return value;
        }
        long absolute = amount.abs().longValue();
        String formatted = formatManwon(absolute);
        String plain = Long.toString(absolute);
        String comma = formatComma(absolute);
        String wrongManwon = wrongManwonText(absolute);
        String longEok = longEokText(absolute);

        String replaced = value
                .replace(comma + "원", formatted)
                .replace(plain + "원", formatted);
        replaced = replaceStandaloneAmount(replaced, comma, formatted);
        replaced = replaceStandaloneAmount(replaced, plain, formatted);
        if (wrongManwon != null) {
            replaced = replaced
                    .replace(wrongManwon, formatted)
                    .replace(wrongManwon.replace(" ", ""), formatted);
        }
        if (longEok != null) {
            replaced = replaced.replace(longEok, formatted);
        }
        return replaced;
    }

    private static String replaceStandaloneAmount(String value, String target, String replacement) {
        return Pattern.compile("(?<![0-9.])" + Pattern.quote(target) + "(?![0-9.%원만억])")
                .matcher(value)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String formatComma(long value) {
        String digits = Long.toString(value);
        StringBuilder formatted = new StringBuilder();
        int firstGroup = digits.length() % 3;
        if (firstGroup == 0) {
            firstGroup = 3;
        }
        formatted.append(digits, 0, firstGroup);
        for (int index = firstGroup; index < digits.length(); index += 3) {
            formatted.append(',').append(digits, index, index + 3);
        }
        return formatted.toString();
    }

    private static String wrongManwonText(long amount) {
        if (amount < 10_000) {
            return null;
        }
        long scaled = amount / 10_000;
        long remainder = amount % 10_000;
        if (remainder == 0) {
            return scaled + "만 원";
        }
        long decimal = Math.round(remainder / 1000.0);
        if (decimal == 10) {
            return (scaled + 1) + "만 원";
        }
        return scaled + "." + decimal + "만 원";
    }

    private static String longEokText(long amount) {
        if (amount < 10_000) {
            return null;
        }
        long eok = amount / 10_000;
        long remainder = amount % 10_000;
        if (remainder == 0 || remainder % 1000 == 0) {
            return null;
        }
        return eok + "." + String.format("%04d", remainder) + "억";
    }

    private static String formatManwon(long amount) {
        if (amount >= 10_000) {
            long eok = amount / 10_000;
            long remainder = amount % 10_000;
            if (remainder == 0) {
                return eok + "억";
            }
            if (remainder % 1000 == 0) {
                return eok + "." + (remainder / 1000) + "억";
            }
            return eok + "억 " + remainder + "만원";
        }
        return amount + "만원";
    }
}
