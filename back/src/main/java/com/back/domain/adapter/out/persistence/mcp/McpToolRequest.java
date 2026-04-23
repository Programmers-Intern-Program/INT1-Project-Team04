package com.back.domain.adapter.out.persistence.mcp;

public record McpToolRequest(
    String toolName,
    String query
) {
}
