package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.notification.NotificationClientProperties;
import com.back.domain.application.port.out.LoadDispatchableNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationSendResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 알림 Dispatcher 테스트")
class NotificationDispatcherServiceTest {

    @Test
    @DisplayName("Application: PENDING Delivery 발송에 성공하면 SENT 상태와 provider message id를 저장한다")
    void marksDeliverySentWhenSenderSucceeds() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 23, 10, 0);
        NotificationDelivery pending = pendingDelivery(0);
        FakeSaveNotificationDeliveryPort savePort = new FakeSaveNotificationDeliveryPort();
        NotificationDispatcherService service = new NotificationDispatcherService(
                fixedLoader(pending),
                List.of(fixedSender(NotificationSendResult.success("provider-1"))),
                savePort,
                new NotificationClientProperties()
        );

        int dispatched = service.dispatchPending(now);

        assertThat(dispatched).isEqualTo(1);
        assertThat(savePort.saved).hasSize(1);
        NotificationDelivery saved = savePort.saved.get(0);
        assertThat(saved.status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(saved.sentAt()).isEqualTo(now);
        assertThat(saved.providerMessageId()).isEqualTo("provider-1");
        assertThat(saved.failureReason()).isNull();
    }

    @Test
    @DisplayName("Application: 일시 실패하면 RETRY 상태와 다음 재시도 시각을 저장한다")
    void marksDeliveryRetryWhenSenderFailsTemporarily() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 23, 10, 0);
        NotificationDelivery pending = pendingDelivery(0);
        FakeSaveNotificationDeliveryPort savePort = new FakeSaveNotificationDeliveryPort();
        NotificationDispatcherService service = new NotificationDispatcherService(
                fixedLoader(pending),
                List.of(fixedSender(NotificationSendResult.retryableFailure("rate limited"))),
                savePort,
                new NotificationClientProperties()
        );

        int dispatched = service.dispatchPending(now);

        assertThat(dispatched).isEqualTo(1);
        NotificationDelivery saved = savePort.saved.get(0);
        assertThat(saved.status()).isEqualTo(NotificationDeliveryStatus.RETRY);
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(saved.nextRetryAt()).isAfter(now);
        assertThat(saved.failureReason()).isEqualTo("rate limited");
    }

    @Test
    @DisplayName("Application: 최대 시도 횟수에 도달한 일시 실패는 FAILED 상태로 저장한다")
    void marksDeliveryFailedWhenRetryAttemptsAreExhausted() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 23, 10, 0);
        NotificationDelivery pending = pendingDelivery(2);
        FakeSaveNotificationDeliveryPort savePort = new FakeSaveNotificationDeliveryPort();
        NotificationDispatcherService service = new NotificationDispatcherService(
                fixedLoader(pending),
                List.of(fixedSender(NotificationSendResult.retryableFailure("rate limited"))),
                savePort,
                new NotificationClientProperties()
        );

        int dispatched = service.dispatchPending(now);

        assertThat(dispatched).isEqualTo(1);
        NotificationDelivery saved = savePort.saved.get(0);
        assertThat(saved.status()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(saved.attemptCount()).isEqualTo(3);
        assertThat(saved.nextRetryAt()).isNull();
        assertThat(saved.failureReason()).isEqualTo("rate limited");
    }

    @Test
    @DisplayName("Application: 지원하는 채널 어댑터가 없으면 FAILED 상태로 저장한다")
    void marksDeliveryFailedWhenNoSenderSupportsChannel() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 23, 10, 0);
        NotificationDelivery pending = pendingDelivery(0);
        FakeSaveNotificationDeliveryPort savePort = new FakeSaveNotificationDeliveryPort();
        NotificationDispatcherService service = new NotificationDispatcherService(
                fixedLoader(pending),
                List.of(),
                savePort,
                new NotificationClientProperties()
        );

        int dispatched = service.dispatchPending(now);

        assertThat(dispatched).isEqualTo(1);
        NotificationDelivery saved = savePort.saved.get(0);
        assertThat(saved.status()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(saved.failureReason()).contains("No notification sender");
    }

    private static LoadDispatchableNotificationDeliveryPort fixedLoader(NotificationDelivery delivery) {
        return now -> List.of(delivery);
    }

    private static SendNotificationDeliveryPort fixedSender(NotificationSendResult result) {
        return new SendNotificationDeliveryPort() {
            @Override
            public boolean supports(NotificationChannel channel) {
                return true;
            }

            @Override
            public NotificationSendResult send(NotificationDelivery delivery) {
                return result;
            }
        };
    }

    private static NotificationDelivery pendingDelivery(int attemptCount) {
        return new NotificationDelivery(
                "delivery-1",
                "alert-1",
                "sub-1",
                1L,
                NotificationChannel.DISCORD_DM,
                "123456789",
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                NotificationDeliveryStatus.PENDING,
                attemptCount,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 4, 23, 9, 0)
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
