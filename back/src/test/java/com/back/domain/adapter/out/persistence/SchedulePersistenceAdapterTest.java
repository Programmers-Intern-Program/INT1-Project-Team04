package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaRepository;
import com.back.domain.adapter.out.persistence.schedule.SchedulePersistenceAdapter;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.model.domain.Domain;
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
@Import(SchedulePersistenceAdapter.class)
@DisplayName("Persistence: 스케줄 영속성 어댑터 테스트")
class SchedulePersistenceAdapterTest {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    @Autowired
    private ScheduleJpaRepository scheduleJpaRepository;

    @Autowired
    private SchedulePersistenceAdapter schedulePersistenceAdapter;

    @Test
    @DisplayName("Persistence: 구독 정보를 포함한 스케줄 저장 테스트")
    void savesScheduleThroughAdapterWithSubscription() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("user@example.com", "token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("real-estate"));
        SubscriptionJpaEntity subscription = subscriptionJpaRepository.save(new SubscriptionJpaEntity(user, domain, "강남구 아파트 실거래가", true));

        LocalDateTime nextRun = LocalDateTime.now().plusHours(1);
        Schedule saved = schedulePersistenceAdapter.save(
                new Schedule(
                        null,
                        new Subscription(
                                subscription.getId(),
                                new User(user.getId(), user.getEmail(), user.getKakaoToken(), user.getCreatedAt()),
                                new Domain(domain.getId(), domain.getName()),
                                subscription.getQuery(),
                                subscription.isActive(),
                                subscription.getCreatedAt()
                        ),
                        "0 0 * * * *",
                        null,
                        nextRun
                )
        );

        assertThat(saved.id()).isNotBlank();
        assertThat(saved.subscription().id()).isEqualTo(subscription.getId());
        assertThat(saved.nextRun()).isEqualTo(nextRun);
        assertThat(scheduleJpaRepository.findById(saved.id())).isPresent();
    }
}
