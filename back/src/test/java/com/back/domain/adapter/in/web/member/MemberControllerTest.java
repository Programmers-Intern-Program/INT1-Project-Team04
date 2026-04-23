package com.back.domain.adapter.in.web.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.service.SessionTokenService;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Web: 회원 API 테스트")
class MemberControllerTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserSessionJpaRepository sessionRepository;

    @Autowired
    private SessionTokenService sessionTokenService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("GET /api/members/me는 현재 회원을 반환한다")
    void getsCurrentMember() throws Exception {
        String cookieHeader = createSession("member-web@example.com", "웹회원");

        HttpResponse<String> response = get("/api/members/me", cookieHeader);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"email\":\"member-web@example.com\"");
        assertThat(response.body()).contains("\"nickname\":\"웹회원\"");
    }

    @Test
    @DisplayName("PATCH /api/members/me는 닉네임을 수정한다")
    void updatesCurrentMemberNickname() throws Exception {
        String cookieHeader = createSession("patch-web@example.com", "이전");

        HttpResponse<String> response = patch("/api/members/me", cookieHeader, """
                {"nickname":"이후"}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"nickname\":\"이후\"");
    }

    @Test
    @DisplayName("DELETE /api/members/me는 탈퇴 처리하고 세션 쿠키를 삭제한다")
    void withdrawsCurrentMember() throws Exception {
        String cookieHeader = createSession("delete-web@example.com", "탈퇴");

        HttpResponse<String> response = delete("/api/members/me", cookieHeader);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.contains("Max-Age=0"));
    }

    private String createSession(String email, String nickname) {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity(email, nickname));
        String rawToken = "raw-" + email;
        sessionRepository.save(new UserSessionJpaEntity(
                user,
                sessionTokenService.hash(rawToken),
                LocalDateTime.now().plusDays(7)
        ));
        return "SESSION=" + rawToken;
    }

    private HttpResponse<String> get(String path, String cookieHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", cookieHeader)
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String cookieHeader, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", cookieHeader)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String cookieHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", cookieHeader)
                .DELETE()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
