package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notification_delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDeliveryJpaEntity {

    @Id
    private String id;

    @Column(name = "alert_event_id", nullable = false)
    private String alertEventId;

    @Column(name = "subscription_id", nullable = false)
    private String subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recipient;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public NotificationDeliveryJpaEntity(
            String alertEventId,
            String subscriptionId,
            Long userId,
            NotificationChannel channel,
            String recipient,
            String title,
            String message,
            NotificationDeliveryStatus status,
            int attemptCount,
            LocalDateTime nextRetryAt,
            LocalDateTime sentAt,
            String failureReason,
            String providerMessageId,
            LocalDateTime createdAt
    ) {
        this.id = UuidGenerator.create();
        this.alertEventId = alertEventId;
        this.subscriptionId = subscriptionId;
        this.userId = userId;
        this.channel = channel;
        this.recipient = recipient;
        this.title = title;
        this.message = message;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextRetryAt = nextRetryAt;
        this.sentAt = sentAt;
        this.failureReason = failureReason;
        this.providerMessageId = providerMessageId;
        this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public static NotificationDeliveryJpaEntity from(NotificationDelivery delivery) {
        NotificationDeliveryJpaEntity entity = new NotificationDeliveryJpaEntity(
                delivery.alertEventId(),
                delivery.subscriptionId(),
                delivery.userId(),
                delivery.channel(),
                delivery.recipient(),
                delivery.title(),
                delivery.message(),
                delivery.status(),
                delivery.attemptCount(),
                delivery.nextRetryAt(),
                delivery.sentAt(),
                delivery.failureReason(),
                delivery.providerMessageId(),
                delivery.createdAt()
        );
        entity.id = delivery.id() == null ? UuidGenerator.create() : delivery.id();
        return entity;
    }

    public NotificationDelivery toDomain() {
        return new NotificationDelivery(
                id,
                alertEventId,
                subscriptionId,
                userId,
                channel,
                recipient,
                title,
                message,
                status,
                attemptCount,
                nextRetryAt,
                sentAt,
                failureReason,
                providerMessageId,
                createdAt
        );
    }
}
