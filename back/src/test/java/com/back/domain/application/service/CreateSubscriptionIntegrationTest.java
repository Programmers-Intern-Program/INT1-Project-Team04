package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import com.back.domain.application.result.SubscriptionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Application Integration: 구독 생성 유스케이스 테스트")
class CreateSubscriptionIntegrationTest {

    @Autowired
    private CreateSubscriptionUseCase createSubscriptionUseCase;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private ScheduleJpaRepository scheduleJpaRepository;

    @Test
    @DisplayName("Application Integration: 구독과 최초 스케줄을 실제 영속성 어댑터로 저장한다")
    void createsSubscriptionAndInitialSchedule() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("integration-user@example.com", "discord-token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("integration-real-estate"));

        SubscriptionResult result = createSubscriptionUseCase.create(new CreateSubscriptionCommand(
                user.getId(),
                domain.getId(),
                "강남구 아파트 실거래가",
                "0 0 * * * *"
        ));

        assertThat(result.id()).isNotBlank();
        assertThat(result.userId()).isEqualTo(user.getId());
        assertThat(result.domainId()).isEqualTo(domain.getId());
        assertThat(result.scheduleId()).isNotBlank();
        assertThat(result.nextRun()).isNotNull();
        assertThat(scheduleJpaRepository.findById(result.scheduleId())).isPresent();
    }
}
