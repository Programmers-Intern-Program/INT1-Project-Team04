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
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(NotificationPersistenceAdapter.class)
@DisplayName("Persistence: 알림 영속성 어댑터 테스트")
class NotificationPersistenceAdapterTest {

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
        SubscriptionJpaEntity subscription = subscriptionJpaRepository.save(new SubscriptionJpaEntity(user, domain, "개정 법률", true));
        ScheduleJpaEntity schedule = scheduleJpaRepository.save(new ScheduleJpaEntity(subscription, "0 0 * * * *", null, LocalDateTime.now().plusHours(1)));

        Notification saved = notificationPersistenceAdapter.save(
            new Notification(
                null,
                new Schedule(
                    schedule.getId(),
                    new Subscription(
                        subscription.getId(),
                        new User(user.getId(), user.getEmail(), user.getKakaoToken(), user.getCreatedAt()),
                        new Domain(domain.getId(), domain.getName()),
                        subscription.getQuery(),
                        subscription.isActive(),
                        subscription.getCreatedAt()
                    ),
                    schedule.getCronExpr(),
                    schedule.getLastRun(),
                    schedule.getNextRun()
                ),
                new User(user.getId(), user.getEmail(), user.getKakaoToken(), user.getCreatedAt()),
                null,
                "KAKAO",
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
