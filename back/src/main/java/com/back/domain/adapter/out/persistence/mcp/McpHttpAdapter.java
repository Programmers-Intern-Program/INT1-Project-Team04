package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.mcp.McpTool;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * [Infrastructure Adapter] 외부 MCP 서버와 HTTP 통신을 담당하는 어댑터
 * * 비즈니스 로직에서 정의한 'ExecuteMcpToolPort' 인터페이스를 구현하며
 * 실제 외부 도구(Tool)를 실행하고 그 결과를 받아오는 역할을 함
 */
@Component
public class McpHttpAdapter implements ExecuteMcpToolPort {
    private final RestClient restClient;

    public McpHttpAdapter(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public McpExecutionResult execute(McpTool tool, String query) {
        McpToolResponse response;
        try {
            response = restClient.post()
                    .uri(tool.server().endpoint())
                    .body(new McpToolRequest(tool.name(), query))
                    .retrieve()
                    .body(McpToolResponse.class);
        } catch (RestClientException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }

        if (response == null) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }

        return new McpExecutionResult(
                response.apiType(),
                response.content(),
                response.metadata());
    }
}
