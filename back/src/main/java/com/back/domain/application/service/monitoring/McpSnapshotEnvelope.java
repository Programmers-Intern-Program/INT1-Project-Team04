package com.back.domain.application.service.monitoring;

import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

public record McpSnapshotEnvelope(JsonNode root, JsonNode summary, JsonNode query) {

    public static Optional<McpSnapshotEnvelope> parseIfSummaryPresent(ObjectMapper objectMapper, String metadataJson) {
        try {
            JsonNode root = objectMapper.readTree(metadataJson == null ? "" : metadataJson);
            if (!root.isObject()) {
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
            JsonNode summary = root.path("structured").path("summary");
            if (!summary.isObject()) {
                return Optional.empty();
            }
            JsonNode query = root.path("structured").path("query");
            return Optional.of(new McpSnapshotEnvelope(
                    root,
                    summary,
                    query.isObject() ? query : objectMapper.createObjectNode()
            ));
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    public static McpSnapshotEnvelope parse(ObjectMapper objectMapper, String metadataJson) {
        try {
            JsonNode root = objectMapper.readTree(metadataJson == null ? "" : metadataJson);
            if (!root.isObject()) {
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
            JsonNode structured = root.path("structured");
            JsonNode summary = structured.path("summary");
            if (!summary.isObject()) {
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
            JsonNode query = structured.path("query");
            return new McpSnapshotEnvelope(
                    root,
                    summary,
                    query.isObject() ? query : objectMapper.createObjectNode()
            );
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    public String subscriptionIdOrNull() {
        String subscriptionId = root.path("execution").path("subscription_id").asText(null);
        return isBlank(subscriptionId) ? null : subscriptionId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
