package com.back.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.back.domain.adapter.out.notification.DiscordDmNotificationDeliveryAdapter;
import com.back.domain.adapter.out.notification.EmailNotificationDeliveryAdapter;
import com.back.domain.adapter.out.notification.NotificationClientProperties;
import com.back.domain.adapter.out.notification.TelegramDmNotificationDeliveryAdapter;
import com.back.domain.application.service.NotificationDeliveryCreationService;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSource;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.notification.NotificationSendResult;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.client.RestClient;

@Tag("live-notification")
@DisplayName("Live Smoke: 실제 외부 알림 발송 테스트")
class NotificationDeliveryLiveSmokeTest {

    @Test
    @DisplayName("Live Smoke: Gmail SMTP로 테스트 이메일을 발송한다")
    void sendsEmailThroughLiveSmtp() {
        assumeChannelEnabled("EMAIL");
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getEmail().setFrom(requiredEnv("APP_NOTIFICATION_EMAIL_FROM"));
        EmailNotificationDeliveryAdapter adapter = new EmailNotificationDeliveryAdapter(liveMailSender(), properties);

        NotificationSendResult result = adapter.send(formattedDelivery(
                NotificationChannel.EMAIL,
                requiredEnv("LIVE_EMAIL_TO")
        ));

        assertThat(result.successful())
                .as(result.failureReason())
                .isTrue();
    }

    @Test
    @DisplayName("Live Smoke: Discord Bot DM으로 테스트 메시지를 발송한다")
    void sendsDiscordDmThroughLiveApi() {
        assumeChannelEnabled("DISCORD_DM");
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getDiscord().setBotToken(requiredEnv("APP_NOTIFICATION_DISCORD_BOT_TOKEN"));
        properties.getDiscord().setApiBaseUrl(env("APP_NOTIFICATION_DISCORD_API_BASE_URL").orElse("https://discord.com/api/v10"));
        DiscordDmNotificationDeliveryAdapter adapter = new DiscordDmNotificationDeliveryAdapter(RestClient.builder(), properties);

        NotificationSendResult result = adapter.send(formattedDelivery(
                NotificationChannel.DISCORD_DM,
                requiredEnv("LIVE_DISCORD_USER_ID")
        ));

        assertThat(result.successful())
                .as(result.failureReason())
                .isTrue();
        assertThat(result.providerMessageId()).isNotBlank();
    }

    @Test
    @DisplayName("Live Smoke: Telegram Bot DM으로 테스트 메시지를 발송한다")
    void sendsTelegramDmThroughLiveApi() {
        assumeChannelEnabled("TELEGRAM_DM");
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getTelegram().setBotToken(requiredEnv("APP_NOTIFICATION_TELEGRAM_BOT_TOKEN"));
        properties.getTelegram().setApiBaseUrl(env("APP_NOTIFICATION_TELEGRAM_API_BASE_URL").orElse("https://api.telegram.org"));
        TelegramDmNotificationDeliveryAdapter adapter = new TelegramDmNotificationDeliveryAdapter(RestClient.builder(), properties);

        NotificationSendResult result = adapter.send(formattedDelivery(
                NotificationChannel.TELEGRAM_DM,
                requiredEnv("LIVE_TELEGRAM_CHAT_ID")
        ));

        assertThat(result.successful())
                .as(result.failureReason())
                .isTrue();
        assertThat(result.providerMessageId()).isNotBlank();
    }

    private JavaMailSenderImpl liveMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(env("APP_MAIL_HOST").orElse("smtp.gmail.com"));
        mailSender.setPort(Integer.parseInt(env("APP_MAIL_PORT").orElse("587")));
        mailSender.setUsername(requiredEnv("APP_MAIL_USERNAME"));
        mailSender.setPassword(requiredEnv("APP_MAIL_PASSWORD"));
        mailSender.setProtocol("smtp");

        Properties mailProperties = mailSender.getJavaMailProperties();
        mailProperties.put("mail.smtp.auth", env("APP_MAIL_SMTP_AUTH").orElse("true"));
        mailProperties.put("mail.smtp.starttls.enable", env("APP_MAIL_SMTP_STARTTLS_ENABLE").orElse("true"));
        mailProperties.put("mail.smtp.connectiontimeout", "10000");
        mailProperties.put("mail.smtp.timeout", "10000");
        mailProperties.put("mail.smtp.writetimeout", "10000");
        return mailSender;
    }

    private NotificationDelivery formattedDelivery(NotificationChannel channel, String recipient) {
        User user = new User(0L, "live-smoke@example.com", "Live Smoke", LocalDateTime.now(), null);
        Subscription subscription = new Subscription(
                "live-subscription",
                user,
                new Domain(10L, "real-estate"),
                "강남구 전세 보증금 3억 이하",
                true,
                LocalDateTime.now()
        );
        AlertEvent alertEvent = new AlertEvent(
                "live-alert",
                subscription,
                "[INT1 live smoke] 강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                "사용자의 지역, 보증금 조건과 일치하는 신규 매물이 발견되었습니다.",
                List.of(
                        new AlertSource(
                                "역삼동 전세 3억",
                                "https://example.com/listings/1",
                                "전용 42m2, 보증금 3억, 강남역 도보 8분"
                        ),
                        new AlertSource(
                                "논현동 전세 2.8억",
                                "https://example.com/listings/2",
                                "전용 39m2, 보증금 2.8억, 2층 남향"
                        )
                ),
                LocalDateTime.now()
        );
        NotificationDeliveryCreationService service = new NotificationDeliveryCreationService(
                subscriptionId -> List.of(new NotificationPreference(
                        "live-preference-" + channel.name().toLowerCase(),
                        subscriptionId,
                        channel,
                        true
                )),
                (userId, requestedChannel) -> Optional.of(new NotificationEndpoint(
                        "live-endpoint-" + requestedChannel.name().toLowerCase(),
                        userId,
                        requestedChannel,
                        recipient,
                        true
                )),
                delivery -> delivery
        );

        return service.createFor(alertEvent).get(0);
    }

    private void assumeChannelEnabled(String channel) {
        assumeTrue(env("LIVE_NOTIFICATION_ENABLED").map(Boolean::parseBoolean).orElse(false),
                "Set LIVE_NOTIFICATION_ENABLED=true to run live notification smoke tests.");
        assumeTrue(selectedChannels().anyMatch(channel::equalsIgnoreCase),
                () -> "Set LIVE_NOTIFICATION_CHANNELS to include " + channel + ".");
    }

    private java.util.stream.Stream<String> selectedChannels() {
        return Arrays.stream(env("LIVE_NOTIFICATION_CHANNELS")
                        .orElse("EMAIL,DISCORD_DM,TELEGRAM_DM")
                        .split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank());
    }

    private String requiredEnv(String name) {
        return env(name)
                .orElseThrow(() -> new AssertionError(name + " is required for selected live notification smoke test."));
    }

    private Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name))
                .map(String::trim)
                .filter(value -> !value.isBlank());
    }
}
