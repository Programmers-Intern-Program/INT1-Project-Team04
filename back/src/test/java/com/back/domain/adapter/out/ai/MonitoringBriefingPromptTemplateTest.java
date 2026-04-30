package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: AI 변화 브리핑 프롬프트 테스트")
class MonitoringBriefingPromptTemplateTest {

    @Test
    @DisplayName("System prompt: JSON 객체만 반환하고 필수 스키마를 포함하도록 지시한다")
    void systemPromptRequiresJsonObjectAndSchema() {
        assertThat(MonitoringBriefingPromptTemplate.SYSTEM_PROMPT)
                .contains("JSON 객체 하나만 반환")
                .contains("마크다운 코드 블록은 사용하지 마세요")
                .contains("\"notificationRecommended\"")
                .contains("\"title\"")
                .contains("\"summary\"")
                .contains("\"keyChanges\"")
                .contains("\"watchPoints\"");
    }

    @Test
    @DisplayName("System prompt: 부동산 금액은 만원 기준을 유지하고 자연스러운 단위로 쓰도록 지시한다")
    void systemPromptDescribesRealEstateMoneyUnits() {
        assertThat(MonitoringBriefingPromptTemplate.SYSTEM_PROMPT)
                .contains("공공 API가 제공한 만원 단위")
                .contains("원 단위로 바꾸지 마세요")
                .contains("10.3억")
                .contains("10억 1231만원")
                .contains("10.1231억");
    }

    @Test
    @DisplayName("User prompt: 백엔드 변화 판단값과 summary JSON을 그대로 포함한다")
    void userPromptIncludesBackendDecisionAndSummaries() {
        String prompt = MonitoringBriefingPromptTemplate.userPrompt(new MonitoringBriefingRequest(
                "강남구 아파트 매매",
                "search_house_price",
                MonitoringChangeDecision.triggered(
                        "avg_deal_amount",
                        BigDecimal.valueOf(101_231),
                        BigDecimal.valueOf(102_000),
                        BigDecimal.valueOf(769),
                        BigDecimal.valueOf(0.76)
                ),
                "{\"avg_deal_amount\":101231}",
                "{\"avg_deal_amount\":102000}",
                "MCP result"
        ));

        assertThat(prompt)
                .contains("[서버 참고 신호]")
                .contains("- 지표: avg_deal_amount")
                .contains("- 이전 값: 101231")
                .contains("- 현재 값: 102000")
                .contains("- 변화량: 769")
                .contains("- 변화율: 0.76%")
                .contains("{\"avg_deal_amount\":101231}")
                .contains("{\"avg_deal_amount\":102000}");
    }
}
