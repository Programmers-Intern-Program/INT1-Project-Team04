package com.back.domain.application.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: MCP 스냅샷 metadata 파서 테스트")
class McpSnapshotEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Application: MCP metadata에서 summary, query, execution context를 구조적으로 읽는다")
    void parsesStructuredSnapshotMetadata() {
        McpSnapshotEnvelope envelope = McpSnapshotEnvelope.parse(objectMapper, """
                {
                  "structured": {
                    "summary": {"avg_deal_amount": 100000, "count": 10},
                    "query": {"region": "강남구", "lawd_cd": "11680", "deal_ymd": "202403"}
                  },
                  "metadata": {"tool_name": "search_house_price"},
                  "execution": {"subscription_id": "sub-1", "schedule_id": "schedule-1"}
                }
                """);

        assertThat(envelope.summary().path("avg_deal_amount").asInt()).isEqualTo(100000);
        assertThat(envelope.query().path("lawd_cd").asText()).isEqualTo("11680");
        assertThat(envelope.subscriptionIdOrNull()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("Application: summary가 없는 MCP metadata는 변화 감지 입력으로 거부한다")
    void rejectsMetadataWithoutSummary() {
        assertThatThrownBy(() -> McpSnapshotEnvelope.parse(objectMapper, "{\"structured\":{\"query\":{}}}"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MCP_REQUEST_FAILED);
    }
}
