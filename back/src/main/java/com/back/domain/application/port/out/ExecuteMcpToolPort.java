package com.back.domain.application.port.out;

import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.mcp.McpTool;
import java.util.Map;

public interface ExecuteMcpToolPort {
    McpExecutionResult execute(McpTool mcpTool, Map<String, Object> arguments);
}
