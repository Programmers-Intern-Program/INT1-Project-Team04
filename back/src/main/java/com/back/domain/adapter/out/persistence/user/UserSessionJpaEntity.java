package com.back.domain.adapter.out.persistence.user;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "user_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_session_token_hash", columnNames = "token_hash")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSessionJpaEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public UserSessionJpaEntity(UserJpaEntity user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
