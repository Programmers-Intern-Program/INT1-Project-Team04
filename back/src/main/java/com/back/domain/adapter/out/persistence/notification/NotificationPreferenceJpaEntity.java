package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationPreference;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
// 스케줄러가 구독마다 findBySubscriptionIdAndEnabledTrue 로 조회
@Table(name = "notification_preference", indexes = {
        @Index(name = "idx_notif_pref_subscription", columnList = "subscription_id, enabled")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreferenceJpaEntity {

    @Id
    private String id;

    @Column(name = "subscription_id", nullable = false)
    private String subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    public NotificationPreferenceJpaEntity(
            String subscriptionId,
            NotificationChannel channel,
            boolean enabled
    ) {
        this.id = UuidGenerator.create();
        this.subscriptionId = subscriptionId;
        this.channel = channel;
        this.enabled = enabled;
    }

    public static NotificationPreferenceJpaEntity from(NotificationPreference preference) {
        NotificationPreferenceJpaEntity entity = new NotificationPreferenceJpaEntity(
                preference.subscriptionId(),
                preference.channel(),
                preference.enabled()
        );
        entity.id = preference.id() == null ? UuidGenerator.create() : preference.id();
        return entity;
    }

    public void disable() {
        this.enabled = false;
    }

    public NotificationPreference toDomain() {
        return new NotificationPreference(
                id,
                subscriptionId,
                channel,
                enabled
        );
    }
}
