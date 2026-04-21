package com.back.domain.adapter.out.persistence.subscription;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [Persistence Entity] 구독 정보를 관리하는 테이블과 매핑
 * * 어떤 사용자가 어떤 도메인에서 어떤 쿼리로 알림이나 데이터를 구독 중인지 기록한다
 */
@Getter
@Entity
@Table(name = "subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionJpaEntity extends BaseTimeEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name ="domain_id", nullable = false)
    private DomainJpaEntity domain;

    @Column(columnDefinition = "TEXT")
    private String query;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public SubscriptionJpaEntity(UserJpaEntity user,
                                 DomainJpaEntity domain,
                                 String query,
                                 boolean active) {
        this.id = UuidGenerator.create();
        this.user = user;
        this.domain = domain;
        this.query = query;
        this.active = active;
    }
}
