package com.back.domain.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Web: OAuth 인증 API 테스트")
class AuthControllerTest extends IntegrationTestBase {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    @DisplayName("authorize는 state 쿠키를 만들고 provider 인증 URL로 리다이렉트한다")
    void redirectsToProviderAuthorizeUrl() throws Exception {
        HttpResponse<String> response = get("/api/auth/oauth/google/authorize");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).contains("https://provider.test/google");
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.contains("OAUTH_STATE="));
    }

    @Test
    @DisplayName("callback은 state를 검증하고 세션 쿠키를 발급한다")
    void callbackCreatesSessionCookie() throws Exception {
        HttpResponse<String> authorize = get("/api/auth/oauth/google/authorize");
        String stateCookie = authorize.headers().allValues("Set-Cookie")
                .stream()
                .filter(cookie -> cookie.startsWith("OAUTH_STATE="))
                .findFirst()
                .orElseThrow();
        String state = stateCookie.substring("OAUTH_STATE=".length(), stateCookie.indexOf(';'));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/auth/oauth/google/callback?code=code-1&state=" + state))
                .header("Cookie", "OAUTH_STATE=" + state)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.contains("SESSION="));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
