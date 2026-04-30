package com.back.domain.application.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 모니터링 스냅샷 대상 매칭 테스트")
class MonitoringQueryMatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MonitoringQueryMatcher matcher = new MonitoringQueryMatcher();

    @Test
    @DisplayName("Application: 같은 법정동 코드와 거래월이면 같은 대상으로 판단한다")
    void matchesSameLawdCdAndDealYmd() throws Exception {
        var previous = objectMapper.readTree("{\"lawd_cd\":\"11680\",\"deal_ymd\":\"202403\"}");
        var current = objectMapper.readTree("{\"lawd_cd\":\"11680\",\"deal_ymd\":\"202403\"}");

        assertThat(matcher.sameTarget(previous, current)).isTrue();
    }

    @Test
    @DisplayName("Application: 법정동 코드가 같아도 거래월이 다르면 다른 대상으로 판단한다")
    void doesNotMatchDifferentDealYmd() throws Exception {
        var previous = objectMapper.readTree("{\"lawd_cd\":\"11680\",\"deal_ymd\":\"202403\"}");
        var current = objectMapper.readTree("{\"lawd_cd\":\"11680\",\"deal_ymd\":\"202404\"}");

        assertThat(matcher.sameTarget(previous, current)).isFalse();
    }
}
