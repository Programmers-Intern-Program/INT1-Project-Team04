package com.back.domain.adapter.out.persistence.mcp;

public record McpToolResponse(
        String apiType,
        String content,
        String metadata
) {
}
