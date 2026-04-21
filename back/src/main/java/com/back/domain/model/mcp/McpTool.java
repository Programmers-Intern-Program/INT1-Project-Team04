package com.back.domain.model.mcp;

import com.back.domain.model.domain.Domain;

public record McpTool(
        Long id,
        McpServer server,
        Domain domain,
        String name,
        String description,
        String inputSchema
) {
}
