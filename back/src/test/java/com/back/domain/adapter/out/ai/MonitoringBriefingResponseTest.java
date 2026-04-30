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
                        .contains("핵심 변화:\n- 10억에서 10.6억으로 상승")
                        .contains("확인할 점:\n- 다음 달 거래량 확인"));
    }

    @Test
    @DisplayName("Adapter: AI가 알림 비추천을 반환하면 응답에 보존한다")
    void parsesNotificationRecommendation() {
        String raw = """
                {"notificationRecommended":false,"title":"표본 부족","summary":"거래 1건만으로는 유의미한 변화로 보기 어렵습니다.","keyChanges":["평균은 상승했지만 표본이 부족합니다."],"watchPoints":["추가 거래 신고 여부 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.notificationRecommended()).isFalse());
    }

    @Test
    @DisplayName("Adapter: 부동산 금액 숫자는 만원 단위 사용자 표현으로 변환한다")
    void formatsRealEstateMoneyAmounts() {
        String raw = """
                {"notificationRecommended":true,"title":"강남구 아파트 평균 매매가 상승","summary":"강남구 아파트 평균 매매가가 100000에서 103000으로 3% 상승했습니다.","keyChanges":["평균 매매가 3000 상승"],"watchPoints":["거래량 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage())
                        .contains("10억")
                        .contains("10.3억")
                        .contains("3000만원")
                        .doesNotContain("100000에서 103000", "10만원", "10.3만원"));
    }
}
