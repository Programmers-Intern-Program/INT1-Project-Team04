package com.back.domain.adapter.out.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.back.domain.adapter.out.persistence.mcp.McpHttpAdapter;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Adapter: MCP 외부 서버 통신 어댑터 테스트")
class McpHttpAdapterTest {

    private McpSyncClient client;
    private McpHttpAdapter adapter;
    private McpTool tool;

    @BeforeEach
    void setUp() {
        client = mock(McpSyncClient.class);
        adapter = new McpHttpAdapter(List.of(client), new ObjectMapper());
        tool = new McpTool(
                1L,
                new McpServer(1L, "monitoring-mcp", "server", "http://localhost:8090"),
                new Domain(1L, "real-estate"),
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
    }

    @Test
    @DisplayName("Adapter: 구조화된 인자로 MCP 도구를 호출하고 결과를 변환한다")
    void executesMcpToolByCallingMcpClientWithStructuredArguments() {
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(McpSchema.Tool.builder().name("search_house_price").build()),
                null,
                null
        ));
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .structuredContent(Map.of(
                        "text", "강남구 202603 아파트 매매 실거래 3건.",
                        "structured", Map.of("summary", Map.of("count", 3)),
                        "source_url", "https://apis.data.go.kr/...",
                        "metadata", Map.of("tool_name", "search_house_price")
                ))
                .build());

        Map<String, Object> arguments = Map.of(
                "input",
                Map.of("region", "강남구", "deal_ymd", "202603")
        );

        McpExecutionResult result = adapter.execute(tool, arguments);

        ArgumentCaptor<McpSchema.CallToolRequest> requestCaptor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(requestCaptor.capture());
        McpSchema.CallToolRequest request = requestCaptor.getValue();

        assertThat(request.name()).isEqualTo("search_house_price");
        assertThat(request.arguments()).isEqualTo(arguments);
        assertThat(result.apiType()).isEqualTo("REAL_ESTATE");
        assertThat(result.content()).isEqualTo("강남구 202603 아파트 매매 실거래 3건.");
        assertThat(result.metadata())
                .contains("\"summary\"")
                .contains("\"source_url\"")
                .contains("\"tool_name\"");
    }

    @Test
    @DisplayName("Adapter: MCP client 호출이 실패하면 표준 API 예외로 변환한다")
    void throwsApiExceptionWhenMcpServerFails() {
        when(client.listTools()).thenThrow(new IllegalStateException("connection failed"));

        assertThatThrownBy(() -> adapter.execute(tool, Map.of("input", Map.of("region", "강남구"))))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MCP_REQUEST_FAILED);
    }
}
