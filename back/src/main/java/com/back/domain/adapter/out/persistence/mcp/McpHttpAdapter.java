package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.mcp.McpTool;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * [Infrastructure Adapter] Spring AI MCP client로 외부 MCP 서버 도구를 실행하는 어댑터
 * * 비즈니스 로직에서 정의한 'ExecuteMcpToolPort' 인터페이스를 구현하며
 * 실제 외부 도구(Tool)를 실행하고 그 결과를 받아오는 역할을 함
 */
@Component
public class McpHttpAdapter implements ExecuteMcpToolPort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final List<McpSyncClient> clients;
    private final ObjectMapper objectMapper;

    public McpHttpAdapter(List<McpSyncClient> clients, ObjectMapper objectMapper) {
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    public McpExecutionResult execute(McpTool tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result;
        try {
            result = clientFor(tool.name()).callTool(new McpSchema.CallToolRequest(tool.name(), arguments));
        } catch (RuntimeException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }

        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }

        Map<String, Object> structured = structuredContent(result);
        return new McpExecutionResult(
                apiType(tool),
                content(result, structured),
                metadata(structured));
    }

    private McpSyncClient clientFor(String toolName) {
        return clients.stream()
                .filter(client -> hasTool(client, toolName))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_REQUEST_FAILED));
    }

    private boolean hasTool(McpSyncClient client, String toolName) {
        McpSchema.ListToolsResult tools = client.listTools();
        return tools != null
                && tools.tools() != null
                && tools.tools().stream().anyMatch(tool -> toolName.equals(tool.name()));
    }

    private Map<String, Object> structuredContent(McpSchema.CallToolResult result) {
        if (result.structuredContent() == null) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(result.structuredContent(), MAP_TYPE);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    private String content(McpSchema.CallToolResult result, Map<String, Object> structured) {
        String text = stringValue(structured.get("text"));
        if (!isBlank(text)) {
            return text;
        }
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .filter(textContent -> !isBlank(textContent))
                .collect(Collectors.joining("\n"));
    }

    private String metadata(Map<String, Object> structured) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("structured", structured.get("structured"));
        metadata.put("source_url", structured.get("source_url"));
        metadata.put("metadata", structured.get("metadata"));
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    private String apiType(McpTool tool) {
        if (tool.domain() == null || isBlank(tool.domain().name())) {
            return tool.name().toUpperCase(Locale.ROOT);
        }
        return tool.domain().name().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
