package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 구조화된 모니터링 조건 테스트")
class StructuredConditionTest {

    @Test
    @DisplayName("퍼센트 상승 조건을 표준 필드로 파싱한다")
    void parsesPercentRiseCondition() {
        Optional<StructuredCondition> parsed = StructuredCondition.parse("5% 이상 상승");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "AVG_PRICE")
                .containsEntry("conditionDirection", "UP")
                .containsEntry("conditionOperator", "GTE")
                .containsEntry("conditionThreshold", "5")
                .containsEntry("conditionUnit", "PERCENT");
    }

    @Test
    @DisplayName("월세 금액 상승 조건을 평균 월세 metric으로 파싱한다")
    void parsesMonthlyRentRiseCondition() {
        Optional<StructuredCondition> parsed = StructuredCondition.parse("월세 5만원 이상 상승");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "AVG_MONTHLY_RENT")
                .containsEntry("conditionDirection", "UP")
                .containsEntry("conditionOperator", "GTE")
                .containsEntry("conditionThreshold", "5")
                .containsEntry("conditionUnit", "MANWON");
    }

    @Test
    @DisplayName("전월세 보증금 조건을 평균 보증금 metric으로 파싱한다")
    void parsesDepositRiseCondition() {
        Optional<StructuredCondition> parsed = StructuredCondition.parse("전월세 보증금 1000만원 이상 상승");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "AVG_DEPOSIT")
                .containsEntry("conditionDirection", "UP")
                .containsEntry("conditionOperator", "GTE")
                .containsEntry("conditionThreshold", "1000")
                .containsEntry("conditionUnit", "MANWON");
    }

    @Test
    @DisplayName("숫자 기준값이 없는 모호한 조건은 거부한다")
    void rejectsVagueCondition() {
        assertThat(StructuredCondition.parse("오르면 알려줘")).isEmpty();
    }

    @Test
    @DisplayName("채용 공고 수 조건 파라미터를 허용한다")
    void acceptsRecruitmentCountConditionParameters() {
        Optional<StructuredCondition> parsed = StructuredCondition.fromParameters(Map.of(
                "conditionMetric", "COUNT",
                "conditionDirection", "UP",
                "conditionOperator", "GTE",
                "conditionThreshold", "1",
                "conditionUnit", "COUNT"
        ));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().metric()).isEqualTo(StructuredCondition.Metric.COUNT);
        assertThat(parsed.get().unit()).isEqualTo(StructuredCondition.Unit.COUNT);
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "COUNT")
                .containsEntry("conditionThreshold", "1")
                .containsEntry("conditionUnit", "COUNT");
    }

    @Test
    @DisplayName("채용 진행중 공고 수 조건 파라미터를 허용한다")
    void acceptsRecruitmentOngoingCountConditionParameters() {
        Optional<StructuredCondition> parsed = StructuredCondition.fromParameters(Map.of(
                "conditionMetric", "ONGOING_COUNT",
                "conditionDirection", "UP",
                "conditionOperator", "GTE",
                "conditionThreshold", "2",
                "conditionUnit", "COUNT"
        ));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().metric()).isEqualTo(StructuredCondition.Metric.ONGOING_COUNT);
        assertThat(parsed.get().unit()).isEqualTo(StructuredCondition.Unit.COUNT);
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "ONGOING_COUNT")
                .containsEntry("conditionThreshold", "2")
                .containsEntry("conditionUnit", "COUNT");
    }

    @Test
    @DisplayName("월세 평균 조건 파라미터를 허용한다")
    void acceptsMonthlyRentConditionParameters() {
        Optional<StructuredCondition> parsed = StructuredCondition.fromParameters(Map.of(
                "conditionMetric", "AVG_MONTHLY_RENT",
                "conditionDirection", "UP",
                "conditionOperator", "GTE",
                "conditionThreshold", "5",
                "conditionUnit", "MANWON"
        ));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().metric()).isEqualTo(StructuredCondition.Metric.AVG_MONTHLY_RENT);
        assertThat(parsed.get().unit()).isEqualTo(StructuredCondition.Unit.MANWON);
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "AVG_MONTHLY_RENT")
                .containsEntry("conditionThreshold", "5")
                .containsEntry("conditionUnit", "MANWON");
    }
}
