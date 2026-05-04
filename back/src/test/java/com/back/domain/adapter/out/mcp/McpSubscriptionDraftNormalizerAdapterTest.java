package com.back.domain.adapter.out.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.mcp.McpSubscriptionDraftNormalizerAdapter;
import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.service.subscriptionconversation.DomainNormalizedSubscriptionDraft;
import com.back.domain.model.mcp.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: MCP 구독 초안 정규화 어댑터")
class McpSubscriptionDraftNormalizerAdapterTest {

    @Test
    @DisplayName("normalize_subscription_draft 도구 응답을 도메인 초안으로 변환한다")
    void convertsMcpStructuredResponseToDomainDraft() {
        FakeExecuteMcpToolPort executePort = new FakeExecuteMcpToolPort("""
                {
                  "structured": {
                    "query": "강남구 아파트 매매 실거래가",
                    "domainName": "real-estate",
                    "intent": "apartment_trade_price",
                    "toolName": "search_house_price",
                    "parameters": {
                      "region": "강남구",
                      "conditionMetric": "AVG_PRICE",
                      "conditionDirection": "UP",
                      "conditionOperator": "GTE",
                      "conditionThreshold": "5",
                      "conditionUnit": "PERCENT"
                    },
                    "missingFields": [],
                    "question": "",
                    "confidence": 0.93
                  },
                  "source_url": null,
                  "metadata": {"tool_name": "normalize_subscription_draft"}
                }
                """);
        McpSubscriptionDraftNormalizerAdapter adapter = new McpSubscriptionDraftNormalizerAdapter(
                executePort,
                new ObjectMapper()
        );
        ParsedTask task = new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "5% 이상 상승",
                "0 9 * * *",
                "telegram",
                "api",
                "강남구 아파트 매매 실거래가",
                List.of(),
                0.9,
                false,
                ""
        );

        Optional<DomainNormalizedSubscriptionDraft> result = adapter.normalize(
                task,
                "강남구 아파트 매매 실거래가 5% 이상 상승하면 텔레그램으로 매일 알려줘",
                null
        );

        assertThat(result).isPresent();
        assertThat(result.get().toolName()).isEqualTo("search_house_price");
        assertThat(result.get().monitoringParams()).containsEntry("region", "강남구");
        assertThat(result.get().missingFields()).isEmpty();
        assertThat(result.get().confidence()).isEqualTo(0.93);
        assertThat(executePort.tool.name()).isEqualTo("normalize_subscription_draft");
        assertThat(executePort.arguments).containsKey("input");
    }

    @Test
    @DisplayName("MCP 응답이 구조화되어 있지 않으면 fallback을 위해 빈 결과를 반환한다")
    void returnsEmptyWhenMcpResponseIsMalformed() {
        FakeExecuteMcpToolPort executePort = new FakeExecuteMcpToolPort("{}");
        McpSubscriptionDraftNormalizerAdapter adapter = new McpSubscriptionDraftNormalizerAdapter(
                executePort,
                new ObjectMapper()
        );

        Optional<DomainNormalizedSubscriptionDraft> result = adapter.normalize(
                new ParsedTask("create", "부동산", "강남구 아파트", "", "", "", "", "", List.of(), 0.5, false, ""),
                "강남구 아파트",
                null
        );

        assertThat(result).isEmpty();
    }

    private static class FakeExecuteMcpToolPort implements ExecuteMcpToolPort {
        private final String metadata;
        private McpTool tool;
        private Map<String, Object> arguments;

        private FakeExecuteMcpToolPort(String metadata) {
            this.metadata = metadata;
        }

        @Override
        public McpExecutionResult execute(McpTool mcpTool, Map<String, Object> arguments) {
            this.tool = mcpTool;
            this.arguments = arguments;
            return new McpExecutionResult("NORMALIZE_SUBSCRIPTION_DRAFT", "구독 초안 구조화 완료.", metadata);
        }
    }
}
