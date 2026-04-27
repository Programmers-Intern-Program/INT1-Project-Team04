package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSource;
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
    @DisplayName("Application: Telegram DM은 모바일에서 빠르게 읽히는 짧은 실제 데이터 알림 본문을 생성한다")
    void createsTelegramOptimizedDeliveryMessage() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = subscription(user);
        AlertEvent alertEvent = alertEvent(subscription);
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
        assertThat(delivery.message()).contains(
                "변화 감지",
                "강남구 전세 매물 변화",
                "신규 2건 감지",
                "1. 역삼동 전세 3억",
                "https://example.com/listings/1",
                "2. 논현동 전세 2.8억",
                "요청: 강남구 전세",
                "감지 시간: 2026-04-24 13:40"
        );
        assertThat(delivery.message()).doesNotContain("**", "<https://", "구독 조건", "감지 시각");
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attemptCount()).isZero();
        assertThat(savePort.saved).hasSize(1);
    }

    @Test
    @DisplayName("Application: Discord DM은 Markdown 강조와 링크 자동 임베드 억제를 적용한 본문을 생성한다")
    void createsDiscordOptimizedDeliveryMessage() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = subscription(user);
        AlertEvent alertEvent = alertEvent(subscription);
        NotificationDeliveryCreationService service = new NotificationDeliveryCreationService(
                subscriptionId -> List.of(new NotificationPreference(
                        "pref-1",
                        subscriptionId,
                        NotificationChannel.DISCORD_DM,
                        true
                )),
                (userId, channel) -> Optional.of(new NotificationEndpoint(
                        "endpoint-1",
                        userId,
                        channel,
                        "987654321012345678",
                        true
                )),
                new FakeSaveNotificationDeliveryPort()
        );

        NotificationDelivery delivery = service.createFor(alertEvent).get(0);

        assertThat(delivery.message()).contains(
                "**변화 감지**",
                "**강남구 전세 매물 변화**",
                "**감지 항목 2건**",
                "1. **역삼동 전세 3억**",
                "<https://example.com/listings/1>",
                "**판단 근거**",
                "**요청**",
                "`강남구 전세`",
                "**감지 시간**"
        );
        assertThat(delivery.message()).doesNotContain("구독 조건", "감지 시각");
    }

    @Test
    @DisplayName("Application: Email은 시작 알림과 같은 컴팩트 HTML 변화 감지 알림을 생성한다")
    void createsEmailOptimizedDeliveryMessage() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = subscription(user);
        AlertEvent alertEvent = alertEvent(subscription);
        NotificationDeliveryCreationService service = new NotificationDeliveryCreationService(
                subscriptionId -> List.of(new NotificationPreference(
                        "pref-1",
                        subscriptionId,
                        NotificationChannel.EMAIL,
                        true
                )),
                (userId, channel) -> Optional.of(new NotificationEndpoint(
                        "endpoint-1",
                        userId,
                        channel,
                        "user@example.com",
                        true
                )),
                new FakeSaveNotificationDeliveryPort()
        );

        NotificationDelivery delivery = service.createFor(alertEvent).get(0);

        assertThat(delivery.message()).startsWith("<!doctype html>");
        assertThat(delivery.message()).contains(
                "max-width:520px",
                "변화 감지</span>",
                "<h1",
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                "감지 항목 2건",
                "역삼동 전세 3억",
                "전용 42m2, 보증금 3억, 강남역 도보 8분",
                "https://example.com/listings/1",
                "논현동 전세 2.8억",
                "판단 근거",
                "요청",
                "강남구 전세",
                "감지 시간",
                "2026-04-24 13:40",
                "role=\"presentation\""
        );
        assertThat(delivery.message()).doesNotContain(
                "[강남구 전세 매물 변화]",
                "구독 조건",
                "감지 시각",
                "font-size:28px"
        );
    }

    @Test
    @DisplayName("Application: 대표 채널의 등록된 수신 endpoint가 없으면 Delivery를 생성하지 않는다")
    void skipsDeliveryWhenEndpointIsMissing() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Subscription subscription = subscription(user);
        AlertEvent alertEvent = alertEvent(subscription);
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

    private Subscription subscription(User user) {
        return new Subscription(
                "sub-1",
                user,
                new Domain(10L, "real-estate"),
                "강남구 전세",
                "create",
                true,
                LocalDateTime.of(2026, 4, 24, 13, 0)
        );
    }

    private AlertEvent alertEvent(Subscription subscription) {
        return new AlertEvent(
                "alert-1",
                subscription,
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                "사용자의 지역/보증금 조건과 일치합니다.",
                List.of(
                        new AlertSource(
                                "역삼동 전세 3억",
                                "https://example.com/listings/1",
                                "전용 42m2, 보증금 3억, 강남역 도보 8분"
                        ),
                        new AlertSource(
                                "논현동 전세 2.8억",
                                "https://example.com/listings/2",
                                "전용 39m2, 보증금 2.8억, 2층 남향"
                        )
                ),
                LocalDateTime.of(2026, 4, 24, 13, 40)
        );
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
