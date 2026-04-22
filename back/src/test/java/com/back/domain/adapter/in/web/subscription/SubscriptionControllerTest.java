package com.back.domain.adapter.in.web.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Web: 구독 생성 API 테스트")
class SubscriptionControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("Web: 구독 생성 요청을 받으면 201 상태와 생성 결과를 반환한다")
    void createsSubscription() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("web-user@example.com", "discord-token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("web-real-estate"));

        HttpResponse<String> response = postSubscription("""
                {
                  "userId": %d,
                  "domainId": %d,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 * * * *"
                }
                """.formatted(user.getId(), domain.getId()));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).contains("\"userId\":" + user.getId());
        assertThat(response.body()).contains("\"domainId\":" + domain.getId());
        assertThat(response.body()).contains("\"query\":\"강남구 아파트 실거래가\"");
        assertThat(response.body()).contains("\"scheduleId\"");
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
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(response.body()).contains("userId");
        assertThat(response.body()).contains("query");
    }

    @Test
    @DisplayName("Web: 존재하지 않는 사용자가 요청하면 404 상태와 표준 오류를 반환한다")
    void returnsApiErrorWhenUserDoesNotExist() throws Exception {
        HttpResponse<String> response = postSubscription("""
                {
                  "userId": 999999999,
                  "domainId": 1,
                  "query": "강남구 아파트 실거래가",
                  "cronExpr": "0 0 * * * *"
                }
                """);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"USER_NOT_FOUND\"");
        assertThat(response.body()).contains("사용자를 찾을 수 없습니다.");
    }

    private HttpResponse<String> postSubscription(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/subscriptions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
