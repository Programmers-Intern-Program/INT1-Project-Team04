package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.adapter.out.persistence.hub.AiDataHubJpaEntity;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.model.notification.NotificationStatus;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * [Persistence Entity] Notification 전송 내역 및 상태를 관리하는 테이블과 매핑
 * * 특정 스케줄에 의해 생성된 AI 분석 결과나 시스템 메시지가
 * * 사용자에게 실제로 어떤 채널을 통해 전달되었는지 기록
 */
@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationJpaEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ScheduleJpaEntity schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_data_hub_id")
    private AiDataHubJpaEntity aiDataHub;

    @Column(nullable = false, length = 50)
    private String channel;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    public NotificationJpaEntity(ScheduleJpaEntity schedule,
                                 UserJpaEntity user,
                                 AiDataHubJpaEntity aiDataHub,
                                 String channel,
                                 String message,
                                 LocalDateTime sentAt,
                                 NotificationStatus status) {
        this.id = UuidGenerator.create();
        this.schedule = schedule;
        this.user = user;
        this.aiDataHub = aiDataHub;
        this.channel = channel;
        this.message = message;
        this.sentAt = sentAt;
        this.status = status;
    }
}
