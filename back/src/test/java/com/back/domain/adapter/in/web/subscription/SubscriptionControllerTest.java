package com.back.domain.adapter.in.web.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationEndpointJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationPreferenceJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.application.service.SessionTokenService;
import com.back.domain.model.notification.NotificationChannel;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Web: 구독 생성 API 테스트")
class SubscriptionControllerTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private UserSessionJpaRepository sessionRepository;

    @Autowired
    private NotificationEndpointJpaRepository notificationEndpointRepository;

    @Autowired
    private NotificationPreferenceJpaRepository notificationPreferenceRepository;

    @Autowired
    private SessionTokenService sessionTokenService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("Web: 구독 생성 요청을 받으면 201 상태와 생성 결과를 반환한다")
    void createsSubscription() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-user@example.com", "웹사용자"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("web-real-estate"));
        String cookieHeader = createSession(user, "subscription-session");

        HttpResponse<String> response = postSubscription("""
                {
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 * * * *"
                }
                """.formatted(domain.getId()), cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).contains("\"userId\":" + user.getId());
        assertThat(response.body()).contains("\"domainId\":" + domain.getId());
        assertThat(response.body()).contains("\"query\":\"강남구 아파트 실거래가\"");
        assertThat(response.body()).contains("\"scheduleId\"");
    }

    @Test
    @DisplayName("Web: 구독 생성 요청의 대표 알림 채널과 수신값을 저장한다")
    void createsNotificationSettingsWithSubscription() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-telegram@example.com", "웹사용자"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("web-real-estate"));
        String cookieHeader = createSession(user, "subscription-notification-session");

        HttpResponse<String> response = postSubscription("""
                {
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 * * * *",
                  "notificationChannel": "TELEGRAM_DM",
                  "notificationTargetAddress": "123456789"
                }
                """.formatted(domain.getId()), cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(notificationEndpointRepository.findByUserIdAndChannelAndEnabledTrue(user.getId(), NotificationChannel.TELEGRAM_DM))
                .get()
                .extracting("targetAddress")
                .isEqualTo("123456789");
        assertThat(notificationPreferenceRepository.findBySubscriptionIdAndEnabledTrue(readSubscriptionId(response.body())))
                .extracting("channel")
                .containsExactly(NotificationChannel.TELEGRAM_DM);
    }

    @Test
    @DisplayName("Web: 필수 요청 값이 없으면 400 상태와 검증 오류를 반환한다")
    void rejectsInvalidRequest() throws Exception {
        HttpResponse<String> response = postSubscription("""
                {
                  "domainId": 1,
                  "query": "",
                  "cronExpr": "0 0 * * * *"
                }
                """, null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(response.body()).contains("query");
    }

    @Test
    @DisplayName("Web: 로그인하지 않고 구독을 생성하면 401을 반환한다")
    void rejectsSubscriptionWithoutSession() throws Exception {
        HttpResponse<String> response = postSubscription("""
                {
                  "domainId": 1,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 * * * *"
                }
                """, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    private String createSession(UserJpaEntity user, String rawToken) {
        sessionRepository.save(new UserSessionJpaEntity(
                user,
                sessionTokenService.hash(rawToken),
                LocalDateTime.now().plusDays(7)
        ));
        return "SESSION=" + rawToken;
    }

    private HttpResponse<String> postSubscription(String body, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/subscriptions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String readSubscriptionId(String responseBody) {
        int start = responseBody.indexOf("\"id\":\"") + 6;
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }
}
