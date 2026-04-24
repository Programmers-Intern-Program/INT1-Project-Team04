package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationChannel;
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
@Table(name = "notification_connection_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationConnectionTokenJpaEntity {

    @Id
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public NotificationConnectionTokenJpaEntity(
            String token,
            Long userId,
            NotificationChannel channel,
            LocalDateTime expiresAt
    ) {
        this.token = token;
        this.userId = userId;
        this.channel = channel;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }
}
