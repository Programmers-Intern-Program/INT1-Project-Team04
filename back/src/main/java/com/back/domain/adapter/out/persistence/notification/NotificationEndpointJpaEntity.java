package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notification_endpoint")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEndpointJpaEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel;

    @Column(name = "target_address", nullable = false, columnDefinition = "TEXT")
    private String targetAddress;

    @Column(nullable = false)
    private boolean enabled;

    public NotificationEndpointJpaEntity(
            Long userId,
            NotificationChannel channel,
            String targetAddress,
            boolean enabled
    ) {
        this.id = UuidGenerator.create();
        this.userId = userId;
        this.channel = channel;
        this.targetAddress = targetAddress;
        this.enabled = enabled;
    }

    public static NotificationEndpointJpaEntity from(NotificationEndpoint endpoint) {
        NotificationEndpointJpaEntity entity = new NotificationEndpointJpaEntity(
                endpoint.userId(),
                endpoint.channel(),
                endpoint.targetAddress(),
                endpoint.enabled()
        );
        entity.id = endpoint.id() == null ? UuidGenerator.create() : endpoint.id();
        return entity;
    }

    public NotificationEndpoint toDomain() {
        return new NotificationEndpoint(
                id,
                userId,
                channel,
                targetAddress,
                enabled
        );
    }
}
