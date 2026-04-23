package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSeverity;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 알림 Delivery 생성 테스트")
class NotificationDeliveryCreationServiceTest {

    @Test
    @DisplayName("Application: AlertEvent가 발생하면 구독의 대표 알림 채널로 PENDING Delivery를 생성한다")
    void createsPendingDeliveryForRepresentativeChannel() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = new Subscription(
                "sub-1",
                user,
                new Domain(10L, "real-estate"),
                "강남구 전세",
                true,
                LocalDateTime.now()
        );
        AlertEvent alertEvent = new AlertEvent(
                "alert-1",
                subscription,
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                "사용자의 지역/보증금 조건과 일치합니다.",
                "https://example.com/listings/1",
                AlertSeverity.HIGH,
                LocalDateTime.now()
        );
        FakeSaveNotificationDeliveryPort savePort = new FakeSaveNotificationDeliveryPort();
        NotificationDeliveryCreationService service = new NotificationDeliveryCreationService(
                subscriptionId -> List.of(new NotificationPreference(
                        "pref-1",
                        subscriptionId,
                        NotificationChannel.TELEGRAM_DM,
                        true
                )),
                (userId, channel) -> Optional.of(new NotificationEndpoint(
                        "endpoint-1",
                        userId,
                        channel,
                        "123456789",
                        true
                )),
                savePort
        );

        List<NotificationDelivery> deliveries = service.createFor(alertEvent);

        assertThat(deliveries).hasSize(1);
        NotificationDelivery delivery = deliveries.get(0);
        assertThat(delivery.alertEventId()).isEqualTo("alert-1");
        assertThat(delivery.subscriptionId()).isEqualTo("sub-1");
        assertThat(delivery.userId()).isEqualTo(1L);
        assertThat(delivery.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(delivery.recipient()).isEqualTo("123456789");
        assertThat(delivery.title()).isEqualTo("강남구 전세 매물 변화");
        assertThat(delivery.message()).contains("조건에 맞는 신규 매물이 2건", "사용자의 지역/보증금 조건");
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attemptCount()).isZero();
        assertThat(savePort.saved).hasSize(1);
    }

    @Test
    @DisplayName("Application: 대표 채널의 등록된 수신 endpoint가 없으면 Delivery를 생성하지 않는다")
    void skipsDeliveryWhenEndpointIsMissing() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = new Subscription(
                "sub-1",
                user,
                new Domain(10L, "real-estate"),
                "강남구 전세",
                true,
                LocalDateTime.now()
        );
        AlertEvent alertEvent = new AlertEvent(
                "alert-1",
                subscription,
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                "사용자의 지역/보증금 조건과 일치합니다.",
                "https://example.com/listings/1",
                AlertSeverity.HIGH,
                LocalDateTime.now()
        );
        FakeSaveNotificationDeliveryPort savePort = new FakeSaveNotificationDeliveryPort();
        NotificationDeliveryCreationService service = new NotificationDeliveryCreationService(
                subscriptionId -> List.of(new NotificationPreference(
                        "pref-1",
                        subscriptionId,
                        NotificationChannel.DISCORD_DM,
                        true
                )),
                (userId, channel) -> Optional.empty(),
                savePort
        );

        List<NotificationDelivery> deliveries = service.createFor(alertEvent);

        assertThat(deliveries).isEmpty();
        assertThat(savePort.saved).isEmpty();
    }

    private static class FakeSaveNotificationDeliveryPort implements SaveNotificationDeliveryPort {

        private final List<NotificationDelivery> saved = new ArrayList<>();

        @Override
        public NotificationDelivery save(NotificationDelivery delivery) {
            saved.add(delivery);
            return delivery;
        }
    }
}
