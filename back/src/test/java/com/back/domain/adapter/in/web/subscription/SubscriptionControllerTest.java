package com.back.domain.adapter.in.web.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationEndpointJpaEntity;
import com.back.domain.adapter.out.persistence.notification.NotificationEndpointJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationDeliveryJpaRepository;
import com.back.domain.adapter.out.persistence.notification.NotificationPreferenceJpaRepository;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.application.service.SessionTokenService;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDeliveryStatus;
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
    private NotificationDeliveryJpaRepository notificationDeliveryRepository;

    @Autowired
    private NotificationPreferenceJpaRepository notificationPreferenceRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionRepository;

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
        assertThat(notificationDeliveryRepository.findAll())
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.getChannel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
                    assertThat(delivery.getRecipient()).isEqualTo("123456789");
                    assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
                    assertThat(delivery.getTitle()).contains("알림 설정");
                });
    }

    @Test
    @DisplayName("Web: 연결된 알림 채널은 수신값 없이 구독에 연결한다")
    void createsNotificationPreferenceWithConnectedEndpoint() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-connected@example.com", "웹사용자"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("web-real-estate"));
        notificationEndpointRepository.save(new NotificationEndpointJpaEntity(
                user.getId(),
                NotificationChannel.DISCORD_DM,
                "discord-user-1",
                true
        ));
        String cookieHeader = createSession(user, "subscription-connected-session");

        HttpResponse<String> response = postSubscription("""
                {
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 * * * *",
                  "notificationChannel": "DISCORD_DM"
                }
                """.formatted(domain.getId()), cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(notificationPreferenceRepository.findBySubscriptionIdAndEnabledTrue(readSubscriptionId(response.body())))
                .extracting("channel")
                .containsExactly(NotificationChannel.DISCORD_DM);
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

    @Test
    @DisplayName("Web: 현재 사용자의 활성 구독 목록을 반환한다")
    void listsCurrentUserActiveSubscriptions() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-list@example.com", "웹사용자"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("real-estate"));
        notificationEndpointRepository.save(new NotificationEndpointJpaEntity(
                user.getId(),
                NotificationChannel.TELEGRAM_DM,
                "123456789",
                true
        ));
        String cookieHeader = createSession(user, "subscription-list-session");
        postSubscription("""
                {
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 9 * * *",
                  "notificationChannel": "TELEGRAM_DM"
                }
                """.formatted(domain.getId()), cookieHeader);

        HttpResponse<String> response = getSubscriptions(cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"query\":\"강남구 아파트 실거래가\"");
        assertThat(response.body()).contains("\"domainLabel\":\"부동산\"");
        assertThat(response.body()).contains("\"cadenceLabel\":\"매일 오전 9시\"");
        assertThat(response.body()).contains("\"notificationChannel\":\"TELEGRAM_DM\"");
        assertThat(response.body()).contains("\"channelLabel\":\"Telegram\"");
        assertThat(response.body()).contains("\"active\":true");
        assertThat(response.body()).contains("\"nextRun\"");
    }

    @Test
    @DisplayName("Web: 같은 사용자에게 같은 도메인, 요청, 주기, 채널의 활성 구독이 있으면 중복 생성을 거부한다")
    void rejectsDuplicateActiveSubscription() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-duplicate@example.com", "웹사용자"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("real-estate"));
        String cookieHeader = createSession(user, "subscription-duplicate-session");

        HttpResponse<String> firstResponse = postSubscription("""
                {
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 9 * * *"
                }
                """.formatted(domain.getId()), cookieHeader);
        HttpResponse<String> duplicateResponse = postSubscription("""
                {
                  "domainId": %d,
                  "query": "  강남구   아파트 실거래가  ",
                  "cronExpr": "0 0 9 * * *"
                }
                """.formatted(domain.getId()), cookieHeader);

        assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(201);
        assertThat(duplicateResponse.statusCode()).as(duplicateResponse.body()).isEqualTo(409);
        assertThat(duplicateResponse.body()).contains("\"code\":\"DUPLICATE_SUBSCRIPTION\"");
        assertThat(subscriptionRepository.findByUserIdAndActiveTrue(user.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Web: 구독 삭제 요청은 현재 사용자의 구독을 비활성화하고 목록에서 제외한다")
    void deletesCurrentUserSubscriptionSoftly() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-delete@example.com", "웹사용자"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("real-estate"));
        String cookieHeader = createSession(user, "subscription-delete-session");
        HttpResponse<String> createResponse = postSubscription("""
                {
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 9 * * *"
                }
                """.formatted(domain.getId()), cookieHeader);
        String subscriptionId = readSubscriptionId(createResponse.body());

        HttpResponse<String> deleteResponse = deleteSubscription(subscriptionId, cookieHeader);
        HttpResponse<String> listResponse = getSubscriptions(cookieHeader);

        assertThat(createResponse.statusCode()).as(createResponse.body()).isEqualTo(201);
        assertThat(deleteResponse.statusCode()).as(deleteResponse.body()).isEqualTo(204);
        assertThat(subscriptionRepository.findById(subscriptionId)).get().extracting("active").isEqualTo(false);
        assertThat(listResponse.statusCode()).as(listResponse.body()).isEqualTo(200);
        assertThat(listResponse.body()).doesNotContain(subscriptionId, "강남구 아파트 실거래가");
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

    private HttpResponse<String> getSubscriptions(String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/subscriptions"))
                .GET();

        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteSubscription(String subscriptionId, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/subscriptions/" + subscriptionId))
                .DELETE();

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
