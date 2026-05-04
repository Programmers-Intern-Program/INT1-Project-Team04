package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
                {"notificationRecommended":true,"title":"강남구 상승","summary":"평균 거래금액이 상승했습니다.","keyChanges":["100000에서 106000으로 상승"],"watchPoints":["다음 달 거래량 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage())
                        .contains("[AI 변화 브리핑] 강남구 상승")
                        .contains("핵심 변화:\n- 10억에서 10.6억으로 상승")
                        .contains("확인할 점:\n- 다음 달 거래량 확인"));
    }

    @Test
    @DisplayName("Adapter: notificationRecommended가 누락되면 브리핑 응답을 거부한다")
    void rejectsMissingNotificationRecommendation() {
        String raw = """
                {"title":"강남구 상승","summary":"평균 거래금액이 상승했습니다.","keyChanges":["6% 상승"],"watchPoints":["거래량 확인"]}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw)).isEmpty();
    }

    @Test
    @DisplayName("Adapter: 허용하지 않은 필드가 있으면 브리핑 응답을 거부한다")
    void rejectsUnexpectedFields() {
        String raw = """
                {"notificationRecommended":true,"title":"강남구 상승","summary":"평균 거래금액이 상승했습니다.","keyChanges":["6% 상승"],"watchPoints":["거래량 확인"],"extra":"불필요한 설명"}
                """;

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw)).isEmpty();
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

    @Test
    @DisplayName("Adapter: 억 단위 소수가 길어질 금액은 억과 만원 조합으로 변환한다")
    void formatsDetailedRealEstateMoneyAmountsWithManwonRemainder() {
        String raw = """
                {"notificationRecommended":true,"title":"강남구 아파트 평균 매매가 상승","summary":"강남구 아파트 평균 매매가가 101231에서 102000으로 상승했습니다.","keyChanges":["평균 매매가 769 상승"],"watchPoints":["거래량 확인"]}
                """;
        MonitoringChangeDecision decision = MonitoringChangeDecision.triggered(
                "avg_deal_amount",
                BigDecimal.valueOf(101_231),
                BigDecimal.valueOf(102_000),
                BigDecimal.valueOf(769),
                BigDecimal.valueOf(0.76)
        );

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage(decision))
                        .contains("10억 1231만원에서 10.2억")
                        .contains("769만원 상승")
                        .doesNotContain("10.1231억", "10.1억에서"));
    }

    @Test
    @DisplayName("Adapter: AI가 원 단위로 잘못 쓴 부동산 금액은 서버 계산값 기준으로 교정한다")
    void correctsAiWonAmountsUsingBackendDecisionValues() {
        String raw = """
                {"notificationRecommended":true,"title":"안산시 아파트 매매가격 하락","summary":"안산시 아파트 평균 매매가격이 100,000원에서 95,000원으로 5% 하락했습니다.","keyChanges":["평균 매매가격 5,000원 하락 (-5%)"],"watchPoints":["추가 데이터 확인"]}
                """;
        MonitoringChangeDecision decision = MonitoringChangeDecision.triggered(
                "avg_deal_amount",
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(95_000),
                BigDecimal.valueOf(-5_000),
                BigDecimal.valueOf(-5)
        );

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage(decision))
                        .contains("10억에서 9.5억")
                        .contains("5000만원 하락")
                        .doesNotContain("100,000원", "95,000원", "5,000원"));
    }

    @Test
    @DisplayName("Adapter: AI가 만 원 단위로 축소해 쓴 부동산 금액도 서버 계산값 기준으로 교정한다")
    void correctsAiManwonTextUsingBackendDecisionValues() {
        String raw = """
                {"notificationRecommended":true,"title":"강동구 아파트 평균 매매가 하락","summary":"강동구 아파트 평균 매매가가 10만 원에서 9만 원으로 10% 하락했습니다.","keyChanges":["평균 매매가 10만 원 → 9만 원으로 10% 감소"],"watchPoints":["시장 변화 확인"]}
                """;
        MonitoringChangeDecision decision = MonitoringChangeDecision.triggered(
                "avg_deal_amount",
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(90_000),
                BigDecimal.valueOf(-10_000),
                BigDecimal.valueOf(-10)
        );

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage(decision))
                        .contains("10억에서 9억")
                        .contains("10억 → 9억")
                        .doesNotContain("10만 원", "9만 원"));
    }

    @Test
    @DisplayName("Adapter: AI가 긴 소수 억 단위로 쓴 부동산 금액도 서버 계산값 기준으로 교정한다")
    void correctsAiLongEokTextUsingBackendDecisionValues() {
        String raw = """
                {"notificationRecommended":true,"title":"강남구 아파트 평균 매매가 상승","summary":"강남구 아파트 평균 매매가가 10.1231억에서 10.2억으로 상승했습니다.","keyChanges":["평균 매매가 769만원 상승"],"watchPoints":["거래량 확인"]}
                """;
        MonitoringChangeDecision decision = MonitoringChangeDecision.triggered(
                "avg_deal_amount",
                BigDecimal.valueOf(101_231),
                BigDecimal.valueOf(102_000),
                BigDecimal.valueOf(769),
                BigDecimal.valueOf(0.76)
        );

        assertThat(MonitoringBriefingResponse.parse(objectMapper, raw))
                .hasValueSatisfying(response -> assertThat(response.toMessage(decision))
                        .contains("10억 1231만원에서 10.2억")
                        .doesNotContain("10.1231억"));
    }
}
