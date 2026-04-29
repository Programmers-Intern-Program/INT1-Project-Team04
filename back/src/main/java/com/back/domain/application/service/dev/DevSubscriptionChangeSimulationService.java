package com.back.domain.application.service.dev;

import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.LoadSubscriptionPort;
import com.back.domain.application.service.NotificationDeliveryCreationService;
import com.back.domain.application.service.NotificationDispatcherService;
import com.back.domain.application.service.monitoring.MonitoringAlertMessageBuilder;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import com.back.domain.application.service.monitoring.MonitoringChangeDetector;
import com.back.domain.application.service.subscriptionconversation.StructuredCondition;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSource;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
@RequiredArgsConstructor
public class DevSubscriptionChangeSimulationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() {};
    private static final BigDecimal DEFAULT_PREVIOUS_VALUE = BigDecimal.valueOf(100_000);

    private final LoadSubscriptionPort loadSubscriptionPort;
    private final LoadSubscriptionMonitoringConfigPort loadMonitoringConfigPort;
    private final MonitoringChangeDetector monitoringChangeDetector;
    private final MonitoringAlertMessageBuilder alertMessageBuilder;
    private final GenerateMonitoringBriefingPort generateMonitoringBriefingPort;
    private final NotificationDeliveryCreationService deliveryCreationService;
    private final NotificationDispatcherService dispatcherService;

    public DevSubscriptionChangeSimulationResult simulate(
            String subscriptionId,
            Long currentUserId,
            LocalDateTime now
    ) {
        Subscription subscription = loadSubscriptionPort.loadActiveByIdAndUserId(subscriptionId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        SubscriptionMonitoringConfig config = loadMonitoringConfigPort.loadBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
        Map<String, Object> rawParameters = parameters(config);
        Map<String, String> parameters = stringParameters(rawParameters);
        StructuredCondition condition = StructuredCondition.fromParameters(parameters)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));

        SummaryPair summaries = fakeSummaries(condition);
        MonitoringChangeDecision decision = monitoringChangeDetector.detect(
                summaries.previous(),
                summaries.current(),
                parameters
        );
        if (!decision.triggered()) {
            return new DevSubscriptionChangeSimulationResult(
                    subscriptionId,
                    false,
                    false,
                    0,
                    0,
                    decision.metricKey(),
                    decision.reason()
            );
        }

        String fallbackMessage = alertMessageBuilder.build(
                subscription.query(),
                config.toolName(),
                decision,
                null
        );
        String mcpContent = fakeMcpContent(subscription, summaries);
        Optional<String> generatedBriefing = generateMonitoringBriefingPort.generate(new MonitoringBriefingRequest(
                subscription.query(),
                config.toolName(),
                decision,
                summaries.previous().toString(),
                summaries.current().toString(),
                mcpContent
        )).filter(briefing -> !briefing.isBlank());
        String message = generatedBriefing.orElse(fallbackMessage);

        List<NotificationDelivery> deliveries = deliveryCreationService.createFor(alertEvent(
                subscription,
                message,
                decision,
                now
        ));
        int dispatchedCount = dispatcherService.dispatchPending(now);

        return new DevSubscriptionChangeSimulationResult(
                subscriptionId,
                true,
                generatedBriefing.isPresent(),
                deliveries.size(),
                dispatchedCount,
                decision.metricKey(),
                decision.reason()
        );
    }

    private SummaryPair fakeSummaries(StructuredCondition condition) {
        BigDecimal comparable = comparableValue(condition);
        BigDecimal delta = deltaValue(DEFAULT_PREVIOUS_VALUE, comparable, condition.unit());
        BigDecimal previous = previousValue(delta);
        BigDecimal signedDelta = signedDelta(delta, condition.direction());
        BigDecimal current = previous.add(signedDelta);

        return new SummaryPair(summaryNode(previous), summaryNode(current));
    }

    private BigDecimal comparableValue(StructuredCondition condition) {
        BigDecimal threshold = thresholdForDetector(condition);
        if ((condition.operator() == StructuredCondition.Operator.LT
                || condition.operator() == StructuredCondition.Operator.LTE)
                && threshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        return switch (condition.operator()) {
            case GTE, LTE -> threshold;
            case GT -> threshold.add(step(condition.unit()));
            case LT -> threshold.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        };
    }

    private BigDecimal thresholdForDetector(StructuredCondition condition) {
        if (condition.unit() == StructuredCondition.Unit.EOK) {
            return condition.threshold().multiply(BigDecimal.valueOf(10_000));
        }
        return condition.threshold();
    }

    private BigDecimal step(StructuredCondition.Unit unit) {
        if (unit == StructuredCondition.Unit.PERCENT) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(100);
    }

    private BigDecimal deltaValue(
            BigDecimal previous,
            BigDecimal comparable,
            StructuredCondition.Unit unit
    ) {
        if (unit == StructuredCondition.Unit.PERCENT) {
            return previous.multiply(comparable)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                    .abs();
        }
        return comparable.abs();
    }

    private BigDecimal previousValue(BigDecimal delta) {
        BigDecimal minimum = delta.multiply(BigDecimal.valueOf(4));
        return DEFAULT_PREVIOUS_VALUE.max(minimum).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal signedDelta(BigDecimal delta, StructuredCondition.Direction direction) {
        BigDecimal rounded = delta.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) == 0) {
            rounded = BigDecimal.ONE;
        }
        if (direction == StructuredCondition.Direction.DOWN) {
            return rounded.negate();
        }
        return rounded;
    }

    private JsonNode summaryNode(BigDecimal value) {
        ObjectNode summary = OBJECT_MAPPER.createObjectNode();
        summary.put("avg_deal_amount", value.setScale(0, RoundingMode.HALF_UP).longValue());
        summary.put("count", 10);
        summary.put("source", "dev-simulated");
        return summary;
    }

    private AlertEvent alertEvent(
            Subscription subscription,
            String message,
            MonitoringChangeDecision decision,
            LocalDateTime now
    ) {
        String title = notificationTitle(message);
        String summary = notificationSummary(message);
        return new AlertEvent(
                UuidGenerator.create(),
                subscription,
                title,
                summary,
                "개발 테스트 버튼으로 fake summary를 생성한 뒤 실제 변화 감지 결과가 조건을 만족했습니다.",
                List.of(new AlertSource(
                        "개발 시뮬레이션: " + decision.metricKey(),
                        null,
                        sourceDescription(decision)
                )),
                now
        );
    }

    private String sourceDescription(MonitoringChangeDecision decision) {
        return "이전 %s, 현재 %s, 변화 %s (%s)".formatted(
                formatNumber(decision.previousValue()),
                formatNumber(decision.currentValue()),
                formatNumber(decision.changeValue()),
                formatPercent(decision.changeRate())
        );
    }

    private String formatPercent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private String formatNumber(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String fakeMcpContent(Subscription subscription, SummaryPair summaries) {
        return """
                [개발 테스트]
                구독 조건: %s
                previousSummary: %s
                currentSummary: %s
                """.formatted(subscription.query(), summaries.previous(), summaries.current()).trim();
    }

    private String notificationTitle(String message) {
        String title = message == null
                ? ""
                : message.lines()
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElse("");
        title = stripPrefix(title.strip(), "[AI 변화 브리핑]");
        title = stripPrefix(title.strip(), "[변화 감지]");
        return isBlank(title) ? "변화 감지 알림" : title;
    }

    private String notificationSummary(String message) {
        return isBlank(message) ? "구독 조건에 맞는 변화가 감지되었습니다." : message.strip();
    }

    private String stripPrefix(String value, String prefix) {
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length()).strip();
        }
        return value;
    }

    private Map<String, Object> parameters(SubscriptionMonitoringConfig config) {
        if (isBlank(config.parametersJson())) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(config.parametersJson(), PARAMETER_MAP);
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Map<String, String> stringParameters(Map<String, Object> parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (value != null) {
                values.put(key, String.valueOf(value));
            }
        });
        return values;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SummaryPair(JsonNode previous, JsonNode current) {
    }
}
