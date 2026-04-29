package com.back.domain.adapter.out.persistence.token;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.model.token.TokenUsageHistory;
import com.back.domain.model.token.TokenUsageType;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "token_usage_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TokenUsageHistoryJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TokenUsageType type;

    @Column(nullable = false)
    private Integer amount;

    @Column(nullable = false)
    private Integer balanceBefore;

    @Column(nullable = false)
    private Integer balanceAfter;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String referenceId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static TokenUsageHistoryJpaEntity fromDomain(TokenUsageHistory domain, UserJpaEntity userEntity) {
        return new TokenUsageHistoryJpaEntity(
                domain.id() != null ? domain.id() : UuidGenerator.create(),
                userEntity,
                domain.type(),
                domain.amount(),
                domain.balanceBefore(),
                domain.balanceAfter(),
                domain.description(),
                domain.referenceId(),
                domain.createdAt() != null ? domain.createdAt() : LocalDateTime.now()
        );
    }

    public TokenUsageHistory toDomain() {
        return new TokenUsageHistory(
                id,
                user.toDomain(),
                type,
                amount,
                balanceBefore,
                balanceAfter,
                description,
                referenceId,
                createdAt
        );
    }
}
