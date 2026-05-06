package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 구독 대화 도메인 기능 registry")
class DomainCapabilityRegistryTest {

    @Test
    @DisplayName("부동산과 채용 구독 intent는 활성화되어 있다")
    void realEstateAndRecruitmentSubscriptionIntentsAreEnabled() {
        DomainCapabilityRegistry registry = new DomainCapabilityRegistry();

        assertThat(registry.requireDomain("real-estate").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.ENABLED);
        assertThat(registry.requireIntent("real-estate", "apartment_trade_price").toolName())
                .isNull();
        assertThat(registry.missingRequiredParameters(
                "real-estate",
                "apartment_trade_price",
                Map.of()
        )).containsExactly("region");

        assertThat(registry.requireDomain("recruitment").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.ENABLED);
        assertThat(registry.requireIntent("recruitment", "job_posting_change").toolName())
                .isNull();
        assertThat(registry.requireIntent("recruitment", "job_posting_change").defaults())
                .containsEntry("dataToolName", "search_public_job");
        assertThat(registry.missingRequiredParameters(
                "recruitment",
                "job_posting_change",
                Map.of()
        )).isEmpty();
    }

    @Test
    @DisplayName("법률과 경매는 기획 상태라 구독 생성 대상이 아니다")
    void lawAndAuctionArePlanned() {
        DomainCapabilityRegistry registry = new DomainCapabilityRegistry();

        assertThat(registry.requireDomain("law-regulation").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.PLANNED);
        assertThat(registry.requireDomain("auction").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.PLANNED);
    }
}
