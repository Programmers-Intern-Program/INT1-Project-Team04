package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationPersistenceAdapter;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaEntity;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaRepository;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.Notification;
import com.back.domain.model.notification.NotificationStatus;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import com.back.support.IntegrationTestBase;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Persistence: 알림 영속성 어댑터 테스트")
class NotificationPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    @Autowired
    private ScheduleJpaRepository scheduleJpaRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    @Autowired
    private NotificationPersistenceAdapter notificationPersistenceAdapter;

    @Test
    @DisplayName("Persistence: AI 데이터 연결이 없는 독립적인 알림 저장 테스트")
    void savesNotificationThroughAdapterWithoutAiDataHub() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("user@example.com", "token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("law"));
        SubscriptionJpaEntity subscription = subscriptionJpaRepository.save(new SubscriptionJpaEntity(user, domain, "개정 법률", "create", true));
        ScheduleJpaEntity schedule = scheduleJpaRepository.save(new ScheduleJpaEntity(subscription, "0 0 * * * *", null, LocalDateTime.now().plusHours(1)));

        Notification saved = notificationPersistenceAdapter.save(
            new Notification(
                null,
                new Schedule(
                    schedule.getId(),
                    subscription.toDomain(),
                    schedule.getCronExpr(),
                    schedule.getLastRun(),
                    schedule.getNextRun()
                ),
                user.toDomain(),
                null,
                "DISCORD",
                "새 알림",
                null,
                NotificationStatus.PENDING
            )
        );

        assertThat(saved.id()).isNotBlank();
        assertThat(saved.aiDataHub()).isNull();
        assertThat(saved.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notificationJpaRepository.findById(saved.id())).isPresent();
    }
}
