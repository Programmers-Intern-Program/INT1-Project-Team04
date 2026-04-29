package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiGatewayMonitoringBriefingAdapterTest {

    @Test
    void generatePostsOpenAiCompatibleChatCompletionsRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiGatewayMonitoringBriefingAdapter adapter = new AiGatewayMonitoringBriefingAdapter(
                builder,
                "https://aigw.alpha.grepp.co/v1",
                "gateway-key",
                "glm-4.5",
                2048,
                0.3,
                new ObjectMapper()
        );

        server.expect(requestTo("https://aigw.alpha.grepp.co/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer gateway-key"))
                .andExpect(content().string(containsString("\"model\":\"glm-4.5\"")))
                .andExpect(content().string(containsString("강남구 아파트 매매")))
                .andRespond(withSuccess("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"title\\":\\"강남구 아파트 매매 상승\\",\\"summary\\":\\"평균 매매가가 3% 상승했습니다.\\",\\"keyChanges\\":[\\"100000에서 103000으로 상승\\"],\\"watchPoints\\":[\\"다음 거래량 확인\\"]}"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.generate(new MonitoringBriefingRequest(
                "강남구 아파트 매매",
                "search_house_price",
                MonitoringChangeDecision.triggered(
                        "avg_deal_amount",
                        BigDecimal.valueOf(100_000),
                        BigDecimal.valueOf(103_000),
                        BigDecimal.valueOf(3_000),
                        BigDecimal.valueOf(3)
                ),
                "{\"avg_deal_amount\":100000}",
                "{\"avg_deal_amount\":103000}",
                "MCP result"
        ));

        assertThat(result)
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("[AI 변화 브리핑] 강남구 아파트 매매 상승")
                        .contains("평균 매매가가 3% 상승했습니다.")
                        .contains("핵심 변화:\n- 100000에서 103000으로 상승"));
        server.verify();
    }

    @Test
    void generateSkipsWhenGatewayIsNotConfigured() {
        RestClient.Builder builder = RestClient.builder();
        AiGatewayMonitoringBriefingAdapter adapter = new AiGatewayMonitoringBriefingAdapter(
                builder,
                "",
                "",
                "glm-4.5",
                2048,
                0.3,
                new ObjectMapper()
        );

        Optional<String> result = adapter.generate(new MonitoringBriefingRequest(
                "강남구 아파트 매매",
                "search_house_price",
                MonitoringChangeDecision.triggered(
                        "avg_deal_amount",
                        BigDecimal.valueOf(100_000),
                        BigDecimal.valueOf(103_000),
                        BigDecimal.valueOf(3_000),
                        BigDecimal.valueOf(3)
                ),
                "{}",
                "{}",
                ""
        ));

        assertThat(result).isEmpty();
    }
}
