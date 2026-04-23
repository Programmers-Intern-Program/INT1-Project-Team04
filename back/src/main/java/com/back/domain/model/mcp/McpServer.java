package com.back.domain.model.mcp;

public record McpServer(
        Long id,
        String name,
        String description,
        String endpoint
) {
}
