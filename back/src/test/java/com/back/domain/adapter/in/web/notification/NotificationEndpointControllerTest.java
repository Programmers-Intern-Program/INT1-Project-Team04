package com.back.domain.adapter.in.web.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.notification.NotificationEndpointJpaEntity;
import com.back.domain.adapter.out.persistence.notification.NotificationEndpointJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.service.SessionTokenService;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.user.OAuthProvider;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.notification.telegram.bot-username=int1_test_bot")
@DisplayName("Web: 알림 채널 연결 API 테스트")
class NotificationEndpointControllerTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserOAuthConnectionJpaRepository oauthConnectionRepository;

    @Autowired
    private UserSessionJpaRepository sessionRepository;

    @Autowired
    private NotificationEndpointJpaRepository endpointRepository;

    @Autowired
    private SessionTokenService sessionTokenService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("Web: Discord OAuth 연결이 있으면 Discord DM endpoint를 저장한다")
    void connectsDiscordEndpointFromOAuthConnection() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("discord-connected@example.com", "웹사용자"));
        oauthConnectionRepository.save(new UserOAuthConnectionJpaEntity(
                user,
                OAuthProvider.DISCORD,
                "discord-user-1",
                "discord-connected@example.com",
                "discord-token"
        ));
        String cookieHeader = createSession(user, "discord-connect-session");

        HttpResponse<String> response = post("/api/notification-endpoints/discord/connect", "{}", cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"channel\":\"DISCORD_DM\"");
        assertThat(response.body()).contains("\"connected\":true");
        assertThat(endpointRepository.findByUserIdAndChannelAndEnabledTrue(user.getId(), NotificationChannel.DISCORD_DM))
                .get()
                .extracting("targetAddress")
                .isEqualTo("discord-user-1");
    }

    @Test
    @DisplayName("Web: Discord OAuth 연결이 없으면 OAuth 시작 URL을 내려준다")
    void returnsDiscordOAuthAuthorizeUrlWhenOAuthConnectionIsMissing() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("discord-missing@example.com", "웹사용자"));
        String cookieHeader = createSession(user, "discord-missing-session");

        HttpResponse<String> response = post("/api/notification-endpoints/discord/connect", "{}", cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"connected\":false");
        assertThat(response.body()).contains("/api/notification-endpoints/discord/authorize");
        assertThat(endpointRepository.findByUserIdAndChannelAndEnabledTrue(user.getId(), NotificationChannel.DISCORD_DM))
                .isEmpty();
    }

    @Test
    @DisplayName("Web: Discord 알림 연결 authorize는 연결용 state 쿠키와 provider 리다이렉트를 만든다")
    void startsDiscordNotificationOAuthConnection() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("discord-authorize@example.com", "웹사용자"));
        String cookieHeader = createSession(user, "discord-authorize-session");

        HttpResponse<String> response = get("/api/notification-endpoints/discord/authorize", cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .contains("https://provider.test/discord");
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.contains("OAUTH_STATE="));
        assertThat(response.headers().allValues("Set-Cookie"))
                .anyMatch(cookie -> cookie.contains("DISCORD_NOTIFICATION_CONNECT=true"));
    }

    @Test
    @DisplayName("Web: Discord 알림 연결 callback은 현재 세션 사용자에 endpoint를 연결하고 로그인 세션을 새로 만들지 않는다")
    void completesDiscordNotificationOAuthForCurrentSession() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("current-user@example.com", "현재사용자"));
        String rawToken = "discord-callback-session";
        String sessionCookie = createSession(user, rawToken);
        HttpResponse<String> authorize = get("/api/notification-endpoints/discord/authorize", sessionCookie);
        String state = readCookieValue(authorize.headers().allValues("Set-Cookie"), "OAUTH_STATE");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/auth/oauth/discord/callback?code=discord-code&state=" + state))
                .header("Cookie", sessionCookie + "; OAUTH_STATE=" + state + "; DISCORD_NOTIFICATION_CONNECT=true")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
        assertThat(response.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("SESSION=") && !cookie.startsWith("SESSION=;"));
        assertThat(oauthConnectionRepository.findFirstByUserIdAndProvider(user.getId(), OAuthProvider.DISCORD))
                .get()
                .extracting("providerUserId")
                .isEqualTo("discord-web-1");
        assertThat(endpointRepository.findByUserIdAndChannelAndEnabledTrue(user.getId(), NotificationChannel.DISCORD_DM))
                .get()
                .extracting("targetAddress")
                .isEqualTo("discord-web-1");
    }

    @Test
    @DisplayName("Web: Telegram deep link를 발급하고 webhook start 명령으로 chat_id endpoint를 저장한다")
    void connectsTelegramEndpointFromDeepLinkWebhook() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("telegram-connected@example.com", "웹사용자"));
        String cookieHeader = createSession(user, "telegram-connect-session");

        HttpResponse<String> startResponse = post("/api/notification-endpoints/telegram/connect", "{}", cookieHeader);

        assertThat(startResponse.statusCode()).as(startResponse.body()).isEqualTo(200);
        String connectUrl = readJsonString(startResponse.body(), "connectUrl");
        assertThat(connectUrl).startsWith("https://t.me/int1_test_bot?start=");
        String token = connectUrl.substring(connectUrl.indexOf("start=") + "start=".length());

        HttpResponse<String> webhookResponse = post("/api/notification-endpoints/telegram/webhook", """
                {
                  "message": {
                    "text": "/start %s",
                    "chat": { "id": 123456789 }
                  }
                }
                """.formatted(token), null);

        assertThat(webhookResponse.statusCode()).as(webhookResponse.body()).isEqualTo(200);
        assertThat(webhookResponse.body()).contains("\"connected\":true");
        assertThat(endpointRepository.findByUserIdAndChannelAndEnabledTrue(user.getId(), NotificationChannel.TELEGRAM_DM))
                .get()
                .extracting("targetAddress")
                .isEqualTo("123456789");
    }

    @Test
    @DisplayName("Web: 연결된 알림 endpoint를 연결 해제하면 비활성화한다")
    void disconnectsNotificationEndpoint() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("telegram-disconnect@example.com", "웹사용자"));
        NotificationEndpointJpaEntity endpoint = endpointRepository.save(new NotificationEndpointJpaEntity(
                user.getId(),
                NotificationChannel.TELEGRAM_DM,
                "123456789",
                true
        ));
        String cookieHeader = createSession(user, "telegram-disconnect-session");

        HttpResponse<String> response = delete("/api/notification-endpoints/TELEGRAM_DM", cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"channel\":\"TELEGRAM_DM\"");
        assertThat(response.body()).contains("\"connected\":false");
        assertThat(endpointRepository.findByUserIdAndChannelAndEnabledTrue(user.getId(), NotificationChannel.TELEGRAM_DM))
                .isEmpty();
        assertThat(endpointRepository.findById(endpoint.getId()))
                .get()
                .extracting("enabled")
                .isEqualTo(false);
    }

    private String createSession(UserJpaEntity user, String rawToken) {
        sessionRepository.save(new UserSessionJpaEntity(
                user,
                sessionTokenService.hash(rawToken),
                LocalDateTime.now().plusDays(7)
        ));
        return "SESSION=" + rawToken;
    }

    private HttpResponse<String> post(String path, String body, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET();

        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .DELETE();

        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String readJsonString(String body, String field) {
        String key = "\"" + field + "\":\"";
        int start = body.indexOf(key) + key.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private String readCookieValue(Iterable<String> cookies, String name) {
        for (String cookie : cookies) {
            if (cookie.startsWith(name + "=")) {
                int end = cookie.indexOf(';');
                return cookie.substring((name + "=").length(), end);
            }
        }
        throw new IllegalStateException("Cookie not found: " + name);
    }
}
