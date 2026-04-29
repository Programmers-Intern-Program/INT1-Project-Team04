package com.back.domain.application.port.out;

import com.back.domain.model.mcp.McpTool;
import java.util.Optional;

/**
 * [Outbound Port] domainId를 기반으로 MCP 도구 정보를 로드하기 위한 인터페이스
 */
public interface LoadMcpToolPort {
    Optional<McpTool> loadByDomainId(Long domainId);

    default Optional<McpTool> loadByDomainIdAndName(Long domainId, String toolName) {
        return loadByDomainId(domainId)
                .filter(tool -> tool.name().equals(toolName));
    }
}
