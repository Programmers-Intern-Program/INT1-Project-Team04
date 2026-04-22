package com.back.domain.adapter.out.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.back.domain.adapter.out.persistence.mcp.McpHttpAdapter;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Adapter: MCP 외부 서버 통신 어댑터 테스트")
class McpHttpAdapterTest {

    @Test
    @DisplayName("Adapter: 도구 이름과 쿼리를 포함하여 외부 MCP 서버에 실행 요청 및 응답 수신 테스트")
    void executesMcpToolByPostingToolNameAndQuery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        McpHttpAdapter adapter = new McpHttpAdapter(builder);

        McpTool tool = new McpTool(
                1L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                new Domain(1L, "real-estate"),
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );

        server.expect(requestTo("http://localhost:8090/tools/execute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                {
                  "toolName": "search_house_price",
                  "query": "강남구 아파트 실거래가"
                }
                """))
                .andRespond(withSuccess("""
                {
                  "apiType": "REAL_ESTATE",
                  "content": "result content",
                  "metadata": "{\"source\":\"mcp\"}"
                }
                """, MediaType.APPLICATION_JSON));

        McpExecutionResult result = adapter.execute(tool, "강남구 아파트 실거래가");

        assertThat(result.apiType()).isEqualTo("REAL_ESTATE");
        assertThat(result.content()).isEqualTo("result content");
        assertThat(result.metadata()).contains("mcp");
        server.verify();
    }
}
