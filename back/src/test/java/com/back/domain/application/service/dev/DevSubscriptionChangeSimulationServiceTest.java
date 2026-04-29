package com.back.domain.application.service.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.adapter.out.notification.NotificationClientProperties;
import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.port.out.LoadDispatchableNotificationDeliveryPort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.LoadSubscriptionPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.application.service.NotificationDeliveryCreationService;
import com.back.domain.application.service.NotificationDispatcherService;
import com.back.domain.application.service.monitoring.MonitoringAlertMessageBuilder;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringChangeDetector;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.notification.NotificationSendResult;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.domain.model.user.User;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 개발용 구독 변화 알림 시뮬레이션 테스트")
class DevSubscriptionChangeSimulationServiceTest {

    @Test
    @DisplayName("Application: 구독 조건 기반 fake summary를 만들어 변화 감지, AI 브리핑, Delivery 발송을 즉시 실행한다")
    void simulatesChangeAlertThroughMonitoringAndDeliveryPipeline() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = new Subscription(
                "sub-1",
                user,
                new Domain(10L, "real-estate"),
                "강남구 아파트 평균 시세 3% 상승",
                "create",
                true,
                LocalDateTime.now()
        );
        DeliveryStore deliveryStore = new DeliveryStore();
        FakeGenerateMonitoringBriefingPort briefingPort =
                new FakeGenerateMonitoringBriefingPort("[AI 변화 브리핑] 강남구 아파트 평균 시세가 상승했습니다.");
        DevSubscriptionChangeSimulationService service = newService(
                (subscriptionId, userId) -> "sub-1".equals(subscriptionId) && userId.equals(1L)
                        ? Optional.of(subscription)
                        : Optional.empty(),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "create",
                        """
                                {
                                  "region": "강남구",
                                  "conditionMetric": "AVG_PRICE",
                                  "conditionDirection": "UP",
                                  "conditionOperator": "GTE",
                                  "conditionThreshold": "3",
                                  "conditionUnit": "PERCENT"
                                }
                                """
                )),
                briefingPort,
                deliveryStore
        );

        DevSubscriptionChangeSimulationResult result = service.simulate(
                "sub-1",
                1L,
                LocalDateTime.of(2026, 4, 29, 9, 30)
        );

        assertThat(result.triggered()).isTrue();
        assertThat(result.briefingGenerated()).isTrue();
        assertThat(result.deliveryCount()).isEqualTo(1);
        assertThat(result.dispatchedCount()).isEqualTo(1);
        assertThat(result.metricKey()).isEqualTo("avg_deal_amount");
        assertThat(briefingPort.requests).singleElement()
                .satisfies(request -> {
                    assertThat(request.subscriptionQuery()).isEqualTo("강남구 아파트 평균 시세 3% 상승");
                    assertThat(request.toolName()).isEqualTo("search_house_price");
                    assertThat(request.previousSummaryJson()).contains("\"avg_deal_amount\":100000");
                    assertThat(request.currentSummaryJson()).contains("\"avg_deal_amount\":103000");
                    assertThat(request.decision().triggered()).isTrue();
                });
        assertThat(deliveryStore.saved).singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.subscriptionId()).isEqualTo("sub-1");
                    assertThat(delivery.userId()).isEqualTo(1L);
                    assertThat(delivery.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
                    assertThat(delivery.recipient()).isEqualTo("123456789");
                    assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.SENT);
                    assertThat(delivery.providerMessageId()).isEqualTo("dev-provider-message-id");
                    assertThat(delivery.message()).contains("강남구 아파트 평균 시세가 상승했습니다.");
                });
    }

    @Test
    @DisplayName("Application: AI 브리핑이 비어 있으면 사용자용 fallback을 보내고 dev JSON을 노출하지 않는다")
    void sendsUserFacingFallbackWithoutDevJsonWhenBriefingIsEmpty() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = new Subscription(
                "sub-1",
                user,
                new Domain(10L, "real-estate"),
                "강남구 아파트 매매",
                "create",
                true,
                LocalDateTime.now()
        );
        DeliveryStore deliveryStore = new DeliveryStore();
        DevSubscriptionChangeSimulationService service = newService(
                (subscriptionId, userId) -> Optional.of(subscription),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "create",
                        """
                                {
                                  "region": "강남구",
                                  "conditionMetric": "AVG_PRICE",
                                  "conditionDirection": "UP",
                                  "conditionOperator": "GTE",
                                  "conditionThreshold": "3",
                                  "conditionUnit": "PERCENT"
                                }
                                """
                )),
                new FakeGenerateMonitoringBriefingPort(null),
                deliveryStore
        );

        DevSubscriptionChangeSimulationResult result = service.simulate(
                "sub-1",
                1L,
                LocalDateTime.of(2026, 4, 29, 17, 0)
        );

        assertThat(result.briefingGenerated()).isFalse();
        assertThat(deliveryStore.saved).singleElement()
                .satisfies(delivery -> {
                    String message = delivery.message();
                    assertThat(message)
                            .contains("강남구 아파트 매매")
                            .contains("- 변화: 3000 (3%)")
                            .doesNotContain(
                                    "[개발 테스트]",
                                    "previousSummary",
                                    "currentSummary",
                                    "dev-simulated"
                            );
                    assertThat(occurrences(message, "[변화 감지]")).isEqualTo(1);
                    assertThat(occurrences(message, "- 도구:")).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("Application: 현재 로그인 사용자 소유 구독이 아니면 시뮬레이션을 거부한다")
    void rejectsSubscriptionOwnedByAnotherUser() {
        DeliveryStore deliveryStore = new DeliveryStore();
        DevSubscriptionChangeSimulationService service = newService(
                (subscriptionId, userId) -> Optional.empty(),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "create",
                        "{}"
                )),
                new FakeGenerateMonitoringBriefingPort("[AI 변화 브리핑]"),
                deliveryStore
        );

        assertThatThrownBy(() -> service.simulate("sub-1", 99L, LocalDateTime.now()))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        assertThat(deliveryStore.saved).isEmpty();
    }

    private DevSubscriptionChangeSimulationService newService(
            LoadSubscriptionPort loadSubscriptionPort,
            LoadSubscriptionMonitoringConfigPort loadMonitoringConfigPort,
            GenerateMonitoringBriefingPort briefingPort,
            DeliveryStore deliveryStore
    ) {
        NotificationDeliveryCreationService deliveryCreationService = new NotificationDeliveryCreationService(
                subscriptionId -> List.of(new NotificationPreference(
                        "preference-1",
                        subscriptionId,
                        NotificationChannel.TELEGRAM_DM,
                        true
                )),
                endpointPort(),
                deliveryStore
        );
        NotificationDispatcherService dispatcherService = new NotificationDispatcherService(
                deliveryStore,
                List.of(successSender()),
                deliveryStore,
                new NotificationClientProperties(),
                new SimpleMeterRegistry()
        );
        return new DevSubscriptionChangeSimulationService(
                loadSubscriptionPort,
                loadMonitoringConfigPort,
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                briefingPort,
                deliveryCreationService,
                dispatcherService
        );
    }

    private LoadNotificationEndpointPort endpointPort() {
        return (userId, channel) -> Optional.of(new NotificationEndpoint(
                "endpoint-1",
                userId,
                channel,
                "123456789",
                true
        ));
    }

    private SendNotificationDeliveryPort successSender() {
        return new SendNotificationDeliveryPort() {
            @Override
            public boolean supports(NotificationChannel channel) {
                return true;
            }

            @Override
            public NotificationSendResult send(NotificationDelivery delivery) {
                return NotificationSendResult.success("dev-provider-message-id");
            }
        };
    }

    private static class FakeGenerateMonitoringBriefingPort implements GenerateMonitoringBriefingPort {
        private final String response;
        private final List<MonitoringBriefingRequest> requests = new ArrayList<>();

        private FakeGenerateMonitoringBriefingPort(String response) {
            this.response = response;
        }

        @Override
        public Optional<String> generate(MonitoringBriefingRequest request) {
            requests.add(request);
            return Optional.ofNullable(response);
        }
    }

    private static class DeliveryStore implements
            SaveNotificationDeliveryPort,
            LoadDispatchableNotificationDeliveryPort {

        private final List<NotificationDelivery> saved = new ArrayList<>();

        @Override
        public NotificationDelivery save(NotificationDelivery delivery) {
            saved.removeIf(existing -> existing.id().equals(delivery.id()));
            saved.add(delivery);
            return delivery;
        }

        @Override
        public List<NotificationDelivery> loadDispatchable(LocalDateTime now) {
            return saved.stream()
                    .filter(delivery -> delivery.status() == NotificationDeliveryStatus.PENDING)
                    .toList();
        }
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
