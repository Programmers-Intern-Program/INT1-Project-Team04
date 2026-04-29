package com.back.domain.application.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 모니터링 변화 감지 테스트")
class MonitoringChangeDetectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MonitoringChangeDetector detector = new MonitoringChangeDetector();

    @Test
    @DisplayName("Application: 평균 매매가가 조건 이상 상승하면 알림 대상으로 판단한다")
    void detectsPercentIncreaseFromSummary() throws Exception {
        JsonNode previous = objectMapper.readTree("{\"avg_deal_amount\":100000,\"count\":10}");
        JsonNode current = objectMapper.readTree("{\"avg_deal_amount\":106000,\"count\":12}");

        MonitoringChangeDecision decision = detector.detect(previous, current, Map.of(
                "conditionMetric", "AVG_PRICE",
                "conditionDirection", "UP",
                "conditionOperator", "GTE",
                "conditionThreshold", "5",
                "conditionUnit", "PERCENT"
        ));

        assertThat(decision.triggered()).isTrue();
        assertThat(decision.metricKey()).isEqualTo("avg_deal_amount");
        assertThat(decision.previousValue()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(decision.currentValue()).isEqualByComparingTo(new BigDecimal("106000"));
        assertThat(decision.changeRate()).isEqualByComparingTo(new BigDecimal("6"));
    }

    @Test
    @DisplayName("Application: 방향이 조건과 다르면 변화가 있어도 알림 대상으로 판단하지 않는다")
    void ignoresChangeWhenDirectionDoesNotMatch() throws Exception {
        JsonNode previous = objectMapper.readTree("{\"avg_deal_amount\":100000,\"count\":10}");
        JsonNode current = objectMapper.readTree("{\"avg_deal_amount\":95000,\"count\":12}");

        MonitoringChangeDecision decision = detector.detect(previous, current, Map.of(
                "conditionMetric", "AVG_PRICE",
                "conditionDirection", "UP",
                "conditionOperator", "GTE",
                "conditionThreshold", "5",
                "conditionUnit", "PERCENT"
        ));

        assertThat(decision.triggered()).isFalse();
    }
}
