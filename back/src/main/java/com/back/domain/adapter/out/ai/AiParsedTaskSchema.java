package com.back.domain.adapter.out.ai;

import com.back.domain.model.notification.NotificationChannel;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

final class AiParsedTaskSchema {
    private static final Set<String> INTENTS = Set.of("create", "delete", "modify", "reject");
    private static final Set<String> DOMAINS = Set.of(
            "부동산", "real-estate",
            "법률", "법률/규제", "law-regulation",
            "채용", "recruitment",
            "경매", "경매/희소매물", "auction"
    );
    private static final Set<String> API_TYPES = Set.of("api", "crawl", "rss", "search");

    private AiParsedTaskSchema() {
    }

    static void validate(JsonNode node) {
        JsonNode meta = node.path("metadata");
        if (!node.isObject() || !meta.isObject()) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }

        String intent = requiredText(node, "intent");
        validateIntent(intent);
        String domainName = optionalText(node, "domain_name");
        validateDomainForIntent(intent, domainName);
        String channel = optionalText(node, "channel");
        validateChannel(channel);
        String apiType = optionalText(node, "api_type");
        validateApiType(apiType);

        double confidence = meta.path("confidence").asDouble(Double.NaN);
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = optionalText(node, fieldName);
        if (value.isBlank()) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static void validateIntent(String value) {
        if (!INTENTS.contains(value)) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private static void validateDomainForIntent(String intent, String domainName) {
        if ("create".equals(intent)) {
            if (domainName.isBlank()) {
                throw new ApiException(ErrorCode.AI_PARSE_FAILED);
            }
            validateDomain(domainName);
        } else if (!domainName.isBlank() && !"reject".equals(intent)) {
            validateDomain(domainName);
        }
    }

    private static void validateDomain(String value) {
        if (!DOMAINS.contains(value)) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private static void validateApiType(String value) {
        if (!value.isBlank() && !API_TYPES.contains(value)) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private static void validateChannel(String value) {
        if (value.isBlank()) {
            return;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if ("TELEGRAM".equals(normalized)) {
            normalized = "TELEGRAM_DM";
        }
        if ("DISCORD".equals(normalized)) {
            normalized = "DISCORD_DM";
        }
        try {
            NotificationChannel.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }
}
