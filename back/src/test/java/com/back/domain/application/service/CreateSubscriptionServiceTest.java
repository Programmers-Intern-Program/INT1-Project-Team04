package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationPreferencePort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
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
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                delivery -> delivery
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
    @DisplayName("Application: Telegram 알림 구독은 짧고 모바일 친화적인 시작 알림을 생성한다")
    void createsNotificationEndpointAndPreferenceWhenChannelIsProvided() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveNotificationEndpointPort saveEndpointPort = new FakeSaveNotificationEndpointPort();
        FakeSaveNotificationPreferencePort savePreferencePort = new FakeSaveNotificationPreferencePort();
        FakeSaveNotificationDeliveryPort saveDeliveryPort = new FakeSaveNotificationDeliveryPort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                new FakeSaveSubscriptionPort(),
                new FakeSaveSchedulePort(),
                (endpointUserId, channel) -> Optional.empty(),
                saveEndpointPort,
                savePreferencePort,
                saveDeliveryPort
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
        assertThat(saveDeliveryPort.saved.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(saveDeliveryPort.saved.recipient()).isEqualTo("123456789");
        assertThat(saveDeliveryPort.saved.subscriptionId()).isEqualTo("sub-1");
        assertThat(saveDeliveryPort.saved.userId()).isEqualTo(user.id());
        assertThat(saveDeliveryPort.saved.title()).isEqualTo("알림 설정이 완료됐어요");
        assertThat(saveDeliveryPort.saved.message()).contains(
                "알림 설정 완료",
                "요청: 강남구 아파트 실거래가",
                "확인 주기: 매시간 정각",
                "변화가 감지되면 이 채널로 핵심만 먼저 알려드릴게요."
        );
        assertThat(saveDeliveryPort.saved.message()).doesNotContain(
                "real-estate",
                "0 0 * * * *",
                "<html",
                "**"
        );
        assertThat(saveDeliveryPort.saved.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(saveDeliveryPort.saved.attemptCount()).isZero();
        assertThat(saveDeliveryPort.saved.sentAt()).isNull();
    }

    @Test
    @DisplayName("Application: Discord 알림 구독은 Markdown 카드형 시작 알림을 생성한다")
    void createsDiscordOptimizedSubscriptionStartedDelivery() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveNotificationDeliveryPort saveDeliveryPort = new FakeSaveNotificationDeliveryPort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                new FakeSaveSubscriptionPort(),
                new FakeSaveSchedulePort(),
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                saveDeliveryPort
        );

        service.createForUser(user.id(), new CreateSubscriptionCommand(
                domain.id(),
                "강남구 아파트 실거래가",
                "0 0 * * * *",
                NotificationChannel.DISCORD_DM,
                "987654321012345678"
        ));

        assertThat(saveDeliveryPort.saved.channel()).isEqualTo(NotificationChannel.DISCORD_DM);
        assertThat(saveDeliveryPort.saved.title()).isEqualTo("알림 설정이 완료됐어요");
        assertThat(saveDeliveryPort.saved.message()).contains(
                "**알림 설정 완료**",
                "**요청**",
                "`강남구 아파트 실거래가`",
                "**감시 영역**",
                "부동산",
                "**확인 주기**",
                "매시간 정각"
        );
        assertThat(saveDeliveryPort.saved.message()).doesNotContain("real-estate", "0 0 * * * *", "<html");
    }

    @Test
    @DisplayName("Application: Email 알림 구독은 컴팩트하고 시인성 높은 HTML 시작 알림을 생성한다")
    void createsEmailHtmlSubscriptionStartedDelivery() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveNotificationDeliveryPort saveDeliveryPort = new FakeSaveNotificationDeliveryPort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                new FakeSaveSubscriptionPort(),
                new FakeSaveSchedulePort(),
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                saveDeliveryPort
        );

        service.createForUser(user.id(), new CreateSubscriptionCommand(
                domain.id(),
                "강남구 아파트 실거래가",
                "0 0 * * * *",
                NotificationChannel.EMAIL,
                "user@example.com"
        ));

        assertThat(saveDeliveryPort.saved.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saveDeliveryPort.saved.title()).isEqualTo("알림 설정이 완료됐어요");
        assertThat(saveDeliveryPort.saved.message()).startsWith("<!doctype html>");
        assertThat(saveDeliveryPort.saved.message()).contains(
                "<h1",
                "max-width:520px",
                "알림 설정 완료</span>",
                "font-size:22px",
                "role=\"presentation\"",
                "알림 설정이 완료됐어요",
                "강남구 아파트 실거래가",
                "부동산",
                "매시간 정각",
                "변화가 감지되면 정리해서 보내드릴게요."
        );
        assertThat(saveDeliveryPort.saved.message()).doesNotContain(
                "WATCH STARTED",
                "font-size:28px",
                "padding:32px",
                "이제부터 요청하신 변화를 지켜보고",
                "새 항목, 판단 근거, 링크",
                "real-estate",
                "0 0 * * * *"
        );
    }

    @Test
    @DisplayName("Application: 알림 수신값이 없으면 사용자의 연결된 endpoint를 사용해 preference를 저장한다")
    void createsNotificationPreferenceWithConnectedEndpoint() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        FakeSaveNotificationEndpointPort saveEndpointPort = new FakeSaveNotificationEndpointPort();
        FakeSaveNotificationPreferencePort savePreferencePort = new FakeSaveNotificationPreferencePort();
        FakeSaveNotificationDeliveryPort saveDeliveryPort = new FakeSaveNotificationDeliveryPort();
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                new FakeSaveSubscriptionPort(),
                new FakeSaveSchedulePort(),
                new FakeLoadNotificationEndpointPort(new NotificationEndpoint(
                        "endpoint-1",
                        user.id(),
                        NotificationChannel.TELEGRAM_DM,
                        "123456789",
                        true
                )),
                saveEndpointPort,
                savePreferencePort,
                saveDeliveryPort
        );

        service.createForUser(user.id(), new CreateSubscriptionCommand(
                domain.id(),
                "강남구 아파트 실거래가",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null
        ));

        assertThat(saveEndpointPort.saved).isNull();
        assertThat(savePreferencePort.saved.subscriptionId()).isEqualTo("sub-1");
        assertThat(savePreferencePort.saved.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(savePreferencePort.saved.enabled()).isTrue();
        assertThat(saveDeliveryPort.saved.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(saveDeliveryPort.saved.recipient()).isEqualTo("123456789");
        assertThat(saveDeliveryPort.saved.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("Application: 알림 채널이 연결되지 않았는데 수신값도 없으면 구독 생성 요청을 거부한다")
    void rejectsNotificationChannelWithoutTargetOrConnectedEndpoint() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(user),
                new FakeLoadDomainPort(domain),
                new FakeSaveSubscriptionPort(),
                new FakeSaveSchedulePort(),
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                delivery -> delivery
        );

        assertThatThrownBy(() -> service.createForUser(user.id(), new CreateSubscriptionCommand(
                domain.id(),
                "강남구 아파트 실거래가",
                "0 0 * * * *",
                NotificationChannel.DISCORD_DM,
                null
        )))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOTIFICATION_ENDPOINT_NOT_CONNECTED);
    }

    @Test
    @DisplayName("Application: 존재하지 않는 사용자로 구독을 생성하면 예외를 발생시킨다")
    void throwsWhenUserDoesNotExist() {
        CreateSubscriptionService service = new CreateSubscriptionService(
                new FakeLoadUserPort(null),
                new FakeLoadDomainPort(new Domain(10L, "real-estate")),
                subscription -> subscription,
                schedule -> schedule,
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                delivery -> delivery
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
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                delivery -> delivery
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
                (endpointUserId, channel) -> Optional.empty(),
                endpoint -> endpoint,
                preference -> preference,
                delivery -> delivery
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

    private record FakeLoadNotificationEndpointPort(NotificationEndpoint endpoint) implements LoadNotificationEndpointPort {

        @Override
        public Optional<NotificationEndpoint> loadEnabledByUserIdAndChannel(Long userId, NotificationChannel channel) {
            if (endpoint == null || !endpoint.userId().equals(userId) || endpoint.channel() != channel) {
                return Optional.empty();
            }

            return Optional.of(endpoint);
        }
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

    private static class FakeSaveNotificationDeliveryPort implements SaveNotificationDeliveryPort {

        private NotificationDelivery saved;

        @Override
        public NotificationDelivery save(NotificationDelivery delivery) {
            this.saved = delivery;
            return delivery;
        }
    }
}
