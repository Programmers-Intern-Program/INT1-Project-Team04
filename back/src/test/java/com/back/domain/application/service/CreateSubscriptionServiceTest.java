package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 구독 생성 비즈니스 로직 테스트")
class CreateSubscriptionServiceTest {

    @Test
    @DisplayName("Application: 구독 생성 요청을 저장하고 초기 실행 스케줄을 생성한다")
    void createsSubscriptionAndInitialSchedule() {
        User user = new User(1L, "user@example.com", "token", LocalDateTime.now());
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveSubscriptionPort saveSubscriptionPort = new FakeSaveSubscriptionPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                saveSubscriptionPort,
                saveSchedulePort
        );

        SubscriptionResult result = service.create(new CreateSubscriptionCommand(
                user.id(),
                domain.id(),
                "강남구 아파트 실거래가",
                "0 0 * * * *"
        ));

        assertThat(saveSubscriptionPort.saved.active()).isTrue();
        assertThat(saveSubscriptionPort.saved.user()).isEqualTo(user);
        assertThat(saveSubscriptionPort.saved.domain()).isEqualTo(domain);
        assertThat(saveSchedulePort.saved.subscription().id()).isEqualTo("sub-1");
        assertThat(saveSchedulePort.saved.cronExpr()).isEqualTo("0 0 * * * *");
        assertThat(saveSchedulePort.saved.nextRun()).isNotNull();
        assertThat(result.id()).isEqualTo("sub-1");
        assertThat(result.scheduleId()).isEqualTo("schedule-1");
        assertThat(result.userId()).isEqualTo(user.id());
        assertThat(result.domainId()).isEqualTo(domain.id());
    }

    @Test
    @DisplayName("Application: 존재하지 않는 사용자로 구독을 생성하면 예외를 발생시킨다")
    void throwsWhenUserDoesNotExist() {
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(null),
                new FakeLoadDomainPort(new Domain(10L, "real-estate")),
                subscription -> subscription,
                schedule -> schedule
        );

        assertThatThrownBy(() -> service.create(new CreateSubscriptionCommand(
                1L,
                10L,
                "강남구 아파트 실거래가",
                "0 0 * * * *"
        )))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("Application: 존재하지 않는 도메인으로 구독을 생성하면 예외를 발생시킨다")
    void throwsWhenDomainDoesNotExist() {
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(new User(1L, "user@example.com", "token", LocalDateTime.now())),
                new FakeLoadDomainPort(null),
                subscription -> subscription,
                schedule -> schedule
        );

        assertThatThrownBy(() -> service.create(new CreateSubscriptionCommand(
                1L,
                10L,
                "강남구 아파트 실거래가",
                "0 0 * * * *"
        )))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOMAIN_NOT_FOUND);
    }

    @Test
    @DisplayName("Application: 유효하지 않은 cron 표현식은 구독 저장 전에 거부한다")
    void rejectsInvalidCronExpressionBeforeSaving() {
        FakeSaveSubscriptionPort saveSubscriptionPort = new FakeSaveSubscriptionPort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(new User(1L, "user@example.com", "token", LocalDateTime.now())),
                new FakeLoadDomainPort(new Domain(10L, "real-estate")),
                saveSubscriptionPort,
                schedule -> schedule
        );

        assertThatThrownBy(() -> service.create(new CreateSubscriptionCommand(
                1L,
                10L,
                "강남구 아파트 실거래가",
                "invalid cron"
        )))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(saveSubscriptionPort.saved).isNull();
    }

    private record FakeLoadUserPort(User user) implements LoadUserPort {

        @Override
        public Optional<User> loadById(Long id) {
            return Optional.ofNullable(user);
        }
    }

    private record FakeLoadDomainPort(Domain domain) implements LoadDomainPort {

        @Override
        public Optional<Domain> loadById(Long domainId) {
            return Optional.ofNullable(domain);
        }
    }

    private static class FakeSaveSubscriptionPort implements SaveSubscriptionPort {

        private Subscription saved;

        @Override
        public Subscription save(Subscription subscription) {
            this.saved = subscription;
            return new Subscription(
                    "sub-1",
                    subscription.user(),
                    subscription.domain(),
                    subscription.query(),
                    subscription.active(),
                    LocalDateTime.now()
            );
        }
    }

    private static class FakeSaveSchedulePort implements SaveSchedulePort {

        private Schedule saved;

        @Override
        public Schedule save(Schedule schedule) {
            this.saved = schedule;
            return new Schedule(
                    "schedule-1",
                    schedule.subscription(),
                    schedule.cronExpr(),
                    schedule.lastRun(),
                    schedule.nextRun()
            );
        }
    }
}
