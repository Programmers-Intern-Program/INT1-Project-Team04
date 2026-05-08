package com.back.domain.adapter.out.persistence.token;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.model.token.UserToken;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserTokenJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserJpaEntity user;

    @Column(nullable = false)
    private Integer balance;

    @Column(nullable = false)
    private Integer totalGranted;

    @Column(nullable = false)
    private Integer totalUsed;

    @Column(nullable = false)
    private LocalDateTime lastUpdatedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static UserTokenJpaEntity fromDomain(UserToken domain, UserJpaEntity userEntity) {
        return new UserTokenJpaEntity(
                domain.id() != null ? domain.id() : UuidGenerator.create(),
                userEntity,
                domain.balance(),
                domain.totalGranted(),
                domain.totalUsed(),
                domain.lastUpdatedAt() != null ? domain.lastUpdatedAt() : LocalDateTime.now(),
                domain.createdAt() != null ? domain.createdAt() : LocalDateTime.now()
        );
    }

    public UserToken toDomain() {
        return new UserToken(
                id,
                user.toDomain(),
                balance,
                totalGranted,
                totalUsed,
                lastUpdatedAt,
                createdAt
        );
    }

    public void update(UserToken domain) {
        this.balance = domain.balance();
        this.totalGranted = domain.totalGranted();
        this.totalUsed = domain.totalUsed();
        this.lastUpdatedAt = LocalDateTime.now();
    }
}
