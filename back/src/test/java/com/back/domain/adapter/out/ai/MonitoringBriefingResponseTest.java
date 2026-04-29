package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: AI 브리핑 응답 검증 테스트")
class MonitoringBriefingResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Adapter: 순수 JSON 객체가 아니면 브리핑 응답을 거부한다")
    void rejectsWrappedJsonResponse() {
        String raw = """
                브리핑 결과입니다.
                {"title":"강남구 상승","summary":"평균 거래금액이 상승했습니다.","keyChanges":["6% 상승"],"watchPoints":["거래량 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw)).isEmpty();
    }

    @Test
    @DisplayName("Adapter: 필수 필드 타입이 맞지 않으면 브리핑 응답을 거부한다")
    void rejectsInvalidFieldTypes() {
        String raw = """
                {"title":"강남구 상승","summary":"평균 거래금액이 상승했습니다.","keyChanges":"6% 상승","watchPoints":["거래량 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw)).isEmpty();
    }

    @Test
    @DisplayName("Adapter: 스키마에 맞는 JSON 객체는 브리핑 메시지로 변환한다")
    void parsesValidBriefingResponse() {
        String raw = """
                {"title":"강남구 상승","summary":"평균 거래금액이 상승했습니다.","keyChanges":["100000에서 106000으로 상승"],"watchPoints":["다음 달 거래량 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage())
                        .contains("[AI 변화 브리핑] 강남구 상승")
                        .contains("핵심 변화:\n- 100000에서 106000으로 상승")
                        .contains("확인할 점:\n- 다음 달 거래량 확인"));
    }
}
