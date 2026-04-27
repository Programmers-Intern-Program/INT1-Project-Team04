package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: subscription conversation capability registry")
class DomainCapabilityRegistryTest {

    @Test
    @DisplayName("only real-estate apartment trade price is enabled")
    void onlyRealEstateApartmentTradePriceIsEnabled() {
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
    }

    @Test
    @DisplayName("law recruitment and auction are planned but not creatable")
    void otherPromptDomainsArePlanned() {
        DomainCapabilityRegistry registry = new DomainCapabilityRegistry();

        assertThat(registry.requireDomain("law-regulation").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.PLANNED);
        assertThat(registry.requireDomain("recruitment").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.PLANNED);
        assertThat(registry.requireDomain("auction").status())
                .isEqualTo(DomainCapabilityRegistry.SupportStatus.PLANNED);
    }
}
