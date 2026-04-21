package com.back.domain.adapter.out.persistence.schedule;

import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [Persistence Entity] 구독 작업의 실행 스케줄을 관리하는 테이블과 매핑
 * * 특정 구독(Subscription)이 언제 실행되었고 언제 실행되어야 하는지
 * * Cron 표현식과 함께 실행 이력을 관리한다
 */
@Getter
@Entity
@Table(name = "schedule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleJpaEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_id", nullable = false)
    private SubscriptionJpaEntity subscription;

    @Column(name = "cron_expr", nullable = false, length = 50)
    private String cronExpr;

    @Column(name = "last_run")
    private LocalDateTime lastRun;

    @Column(name = "next_run")
    private LocalDateTime nextRun;

    public ScheduleJpaEntity(SubscriptionJpaEntity subscription,
                             String cronExpr,
                             LocalDateTime lastRun,
                             LocalDateTime nextRun) {
        this.id = UuidGenerator.create();
        this.subscription = subscription;
        this.cronExpr = cronExpr;
        this.lastRun = lastRun;
        this.nextRun = nextRun;
    }
}
