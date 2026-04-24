package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationPreferencePort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 구독 생성 비즈니스 로직 테스트")
class CreateSubscriptionServiceTest {

    @Test
    @DisplayName("Application: 구독 생성 요청을 저장하고 초기 실행 스케줄을 생성한다")
    void createsSubscriptionAndInitialSchedule() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveSubscriptionPort saveSubscriptionPort = new FakeSaveSubscriptionPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                saveSubscriptionPort,
                saveSchedulePort,
                endpoint -> endpoint,
                preference -> preference
        );

        SubscriptionResult result = service.createForUser(user.id(), new CreateSubscriptionCommand(
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
    @DisplayName("Application: 구독 생성 요청에 알림 채널이 포함되면 endpoint와 대표 preference를 저장한다")
    void createsNotificationEndpointAndPreferenceWhenChannelIsProvided() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveNotificationEndpointPort saveEndpointPort = new FakeSaveNotificationEndpointPort();
        FakeSaveNotificationPreferencePort savePreferencePort = new FakeSaveNotificationPreferencePort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                new FakeSaveSubscriptionPort(),
                new FakeSaveSchedulePort(),
                saveEndpointPort,
                savePreferencePort
        );

        service.createForUser(user.id(), new CreateSubscriptionCommand(
                domain.id(),
                "강남구 아파트 실거래가",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                "123456789"
        ));

        assertThat(saveEndpointPort.saved.userId()).isEqualTo(user.id());
        assertThat(saveEndpointPort.saved.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(saveEndpointPort.saved.targetAddress()).isEqualTo("123456789");
        assertThat(saveEndpointPort.saved.enabled()).isTrue();
        assertThat(savePreferencePort.saved.subscriptionId()).isEqualTo("sub-1");
        assertThat(savePreferencePort.saved.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(savePreferencePort.saved.enabled()).isTrue();
    }

    @Test
    @DisplayName("Application: 존재하지 않는 사용자로 구독을 생성하면 예외를 발생시킨다")
    void throwsWhenUserDoesNotExist() {
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(null),
                new FakeLoadDomainPort(new Domain(10L, "real-estate")),
                subscription -> subscription,
                schedule -> schedule,
                endpoint -> endpoint,
                preference -> preference
        );

        assertThatThrownBy(() -> service.createForUser(1L, new CreateSubscriptionCommand(
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
                new FakeLoadUserPort(new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null)),
                new FakeLoadDomainPort(null),
                subscription -> subscription,
                schedule -> schedule,
                endpoint -> endpoint,
                preference -> preference
        );

        assertThatThrownBy(() -> service.createForUser(1L, new CreateSubscriptionCommand(
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
                new FakeLoadUserPort(new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null)),
                new FakeLoadDomainPort(new Domain(10L, "real-estate")),
                saveSubscriptionPort,
                schedule -> schedule,
                endpoint -> endpoint,
                preference -> preference
        );

        assertThatThrownBy(() -> service.createForUser(1L, new CreateSubscriptionCommand(
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

        @Override
        public List<Domain> loadAll() {
            return domain == null ? List.of() : List.of(domain);
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

    private static class FakeSaveNotificationEndpointPort implements SaveNotificationEndpointPort {

        private NotificationEndpoint saved;

        @Override
        public NotificationEndpoint save(NotificationEndpoint endpoint) {
            this.saved = endpoint;
            return endpoint;
        }
    }

    private static class FakeSaveNotificationPreferencePort implements SaveNotificationPreferencePort {

        private NotificationPreference saved;

        @Override
        public NotificationPreference save(NotificationPreference preference) {
            this.saved = preference;
            return preference;
        }
    }
}
