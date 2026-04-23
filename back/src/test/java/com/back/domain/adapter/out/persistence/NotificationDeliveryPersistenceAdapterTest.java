package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.notification.NotificationDeliveryPersistenceAdapter;
import com.back.domain.adapter.out.persistence.notification.NotificationEndpointPersistenceAdapter;
import com.back.domain.adapter.out.persistence.notification.NotificationPreferencePersistenceAdapter;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.support.IntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Persistence: 알림 Delivery/Preference/Endpoint 어댑터 테스트")
class NotificationDeliveryPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired
    private NotificationEndpointPersistenceAdapter endpointAdapter;

    @Autowired
    private NotificationPreferencePersistenceAdapter preferenceAdapter;

    @Autowired
    private NotificationDeliveryPersistenceAdapter deliveryAdapter;

    @Test
    @DisplayName("Persistence: 사용자 채널 endpoint와 구독 대표 채널 preference를 저장하고 조회한다")
    void savesAndLoadsEndpointAndPreference() {
        NotificationEndpoint endpoint = endpointAdapter.save(new NotificationEndpoint(
                null,
                1L,
                NotificationChannel.TELEGRAM_DM,
                "123456789",
                true
        ));
        NotificationPreference preference = preferenceAdapter.save(new NotificationPreference(
                null,
                "sub-1",
                NotificationChannel.TELEGRAM_DM,
                true
        ));

        assertThat(endpoint.id()).isNotBlank();
        assertThat(preference.id()).isNotBlank();
        assertThat(endpointAdapter.loadEnabledByUserIdAndChannel(1L, NotificationChannel.TELEGRAM_DM))
                .contains(endpoint);
        assertThat(preferenceAdapter.loadEnabledBySubscriptionId("sub-1"))
                .containsExactly(preference);
    }

    @Test
    @DisplayName("Persistence: 같은 구독에 새 대표 채널을 저장하면 기존 enabled preference를 비활성화한다")
    void keepsOnlyOneEnabledPreferencePerSubscription() {
        NotificationPreference telegram = preferenceAdapter.save(new NotificationPreference(
                null,
                "sub-1",
                NotificationChannel.TELEGRAM_DM,
                true
        ));
        NotificationPreference email = preferenceAdapter.save(new NotificationPreference(
                null,
                "sub-1",
                NotificationChannel.EMAIL,
                true
        ));

        assertThat(preferenceAdapter.loadEnabledBySubscriptionId("sub-1"))
                .containsExactly(email);
        assertThat(preferenceAdapter.loadEnabledBySubscriptionId("sub-1"))
                .doesNotContain(telegram);
    }

    @Test
    @DisplayName("Persistence: PENDING 및 재시도 시각이 지난 RETRY Delivery만 발송 대상으로 조회한다")
    void loadsOnlyDispatchableDeliveries() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 23, 10, 0);
        NotificationDelivery pending = deliveryAdapter.save(delivery(
                "delivery-pending",
                NotificationDeliveryStatus.PENDING,
                0,
                null
        ));
        NotificationDelivery retryDue = deliveryAdapter.save(delivery(
                "delivery-retry-due",
                NotificationDeliveryStatus.RETRY,
                1,
                now.minusMinutes(1)
        ));
        deliveryAdapter.save(delivery(
                "delivery-retry-later",
                NotificationDeliveryStatus.RETRY,
                1,
                now.plusMinutes(1)
        ));
        deliveryAdapter.save(delivery(
                "delivery-sent",
                NotificationDeliveryStatus.SENT,
                1,
                null
        ));

        assertThat(deliveryAdapter.loadDispatchable(now))
                .extracting(NotificationDelivery::id)
                .containsExactly(pending.id(), retryDue.id());
    }

    private NotificationDelivery delivery(
            String id,
            NotificationDeliveryStatus status,
            int attemptCount,
            LocalDateTime nextRetryAt
    ) {
        return new NotificationDelivery(
                id,
                "alert-1",
                "sub-1",
                1L,
                NotificationChannel.EMAIL,
                "user@example.com",
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                status,
                attemptCount,
                nextRetryAt,
                status == NotificationDeliveryStatus.SENT ? LocalDateTime.now() : null,
                null,
                null,
                LocalDateTime.of(2026, 4, 23, 9, 0)
        );
    }
}
