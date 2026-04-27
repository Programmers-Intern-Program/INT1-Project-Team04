package com.back.domain.application.service.subscriptionconversation;

import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DomainCapabilityRegistry {

    private static final Map<String, DomainCapability> CAPABILITIES = Map.of(
            "real-estate", new DomainCapability(
                    "real-estate",
                    "부동산",
                    SupportStatus.ENABLED,
                    List.of(new IntentCapability(
                            "apartment_trade_price",
                            "search_house_price",
                            List.of("region"),
                            Map.of("dealYmdPolicy", "LATEST_AVAILABLE_MONTH")
                    ))
            ),
            "law-regulation", new DomainCapability("law-regulation", "법률/규제", SupportStatus.PLANNED, List.of()),
            "recruitment", new DomainCapability("recruitment", "채용", SupportStatus.PLANNED, List.of()),
            "auction", new DomainCapability("auction", "경매/희소매물", SupportStatus.PLANNED, List.of())
    );

    public Optional<DomainCapability> findDomain(String domainName) {
        return Optional.ofNullable(CAPABILITIES.get(domainName));
    }

    public DomainCapability requireDomain(String domainName) {
        return findDomain(domainName).orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
    }

    public Optional<IntentCapability> findIntent(String domainName, String intentId) {
        return findDomain(domainName)
                .flatMap(domain -> domain.intents().stream()
                        .filter(intent -> intent.id().equals(intentId))
                        .findFirst());
    }

    public IntentCapability requireIntent(String domainName, String intentId) {
        return findIntent(domainName, intentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
    }

    public List<String> missingRequiredParameters(String domainName, String intentId, Map<String, String> params) {
        return requireIntent(domainName, intentId).requiredFields().stream()
                .filter(field -> !params.containsKey(field) || params.get(field).isBlank())
                .toList();
    }

    public record DomainCapability(
            String domainName,
            String label,
            SupportStatus status,
            List<IntentCapability> intents
    ) {
    }

    public record IntentCapability(
            String id,
            String toolName,
            List<String> requiredFields,
            Map<String, String> defaults
    ) {
    }

    public enum SupportStatus {
        ENABLED,
        PLANNED,
        DISABLED
    }
}
