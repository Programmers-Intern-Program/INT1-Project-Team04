package com.back.domain.adapter.out.notification;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.PromoteSubscriptionBaselinePort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.application.result.PromoteBaselineMcpResult;
import com.back.domain.model.mcp.McpTool;
import com.back.global.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpBaselinePromoteAdapter implements PromoteSubscriptionBaselinePort {

    private static final McpTool PROMOTE_TOOL = new McpTool(
            null,
            null,
            null,
            "promote_subscription_baseline",
            "MCP baseline promote tool",
            "{}"
    );

    private final ExecuteMcpToolPort executeMcpToolPort;
    private final ObjectMapper objectMapper;

    @Override
    public PromoteBaselineMcpResult promote(String subscriptionId, String paramsHash) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subscriptionId", subscriptionId);
        if (paramsHash != null && !paramsHash.isBlank()) {
            input.put("paramsHash", paramsHash);
        }

        try {
            McpExecutionResult result = executeMcpToolPort.execute(
                    PROMOTE_TOOL,
                    Map.of("input", input)
            );
            return parseResult(result);
        } catch (ApiException exception) {
            log.warn("MCP promote_subscription_baseline failed: subscriptionId={}, message={}",
                    subscriptionId, exception.getMessage());
            return PromoteBaselineMcpResult.failed("mcp request failed");
        }
    }

    private PromoteBaselineMcpResult parseResult(McpExecutionResult result) {
        try {
            String metadata = result.metadata() == null ? "{}" : result.metadata();
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode structured = root.path("structured");
            boolean promoted = structured.path("promoted").asBoolean(false);
            int rowsUpdated = structured.path("rows_updated").asInt(0);
            JsonNode skipped = structured.path("skipped_reason");
            String skippedReason = skipped.isMissingNode() || skipped.isNull()
                    ? null
                    : skipped.asText();
            return new PromoteBaselineMcpResult(promoted, rowsUpdated, skippedReason);
        } catch (JsonProcessingException exception) {
            return PromoteBaselineMcpResult.failed("mcp response parse failed");
        }
    }
}
