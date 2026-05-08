package com.back.domain.adapter.out.persistence.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "baseline_promote_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BaselinePromoteTokenJpaEntity {

    @Id
    private String token;

    @Column(name = "subscription_id", nullable = false, length = 64)
    private String subscriptionId;

    @Column(name = "params_hash", length = 64)
    private String paramsHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "notification_id", length = 36)
    private String notificationId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public BaselinePromoteTokenJpaEntity(
            String token,
            String subscriptionId,
            String paramsHash,
            Long userId,
            String notificationId,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt
    ) {
        this.token = token;
        this.subscriptionId = subscriptionId;
        this.paramsHash = paramsHash;
        this.userId = userId;
        this.notificationId = notificationId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }
}
