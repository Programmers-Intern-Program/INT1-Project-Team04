package com.back.domain.model.hub;

import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.user.User;
import java.time.LocalDateTime;

public record AiDataHub(
        String id,
        User user,
        McpTool mcpTool,
        String apiType,
        String content,
        String embedding,
        String metadata,
        LocalDateTime createdAt
) {
}
