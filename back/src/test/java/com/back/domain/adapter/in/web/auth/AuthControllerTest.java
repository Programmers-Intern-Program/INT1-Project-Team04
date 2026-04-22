package com.back.domain.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.oauth.OAuthProviderClient;
import com.back.domain.adapter.out.oauth.OAuthProviderClientRegistry;
import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Web: OAuth 인증 API 테스트")
class AuthControllerTest {

    @LocalServerPort
    private int port;

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

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @TestConfiguration
    static class FakeOAuthConfiguration {

        @Bean
        @Primary
        OAuthProviderClientRegistry fakeOAuthProviderClientRegistry() {
            return new OAuthProviderClientRegistry(List.of(new FakeGoogleOAuthProviderClient()));
        }
    }

    private static class FakeGoogleOAuthProviderClient implements OAuthProviderClient {

        @Override
        public OAuthProvider provider() {
            return OAuthProvider.GOOGLE;
        }

        @Override
        public URI authorizationUri(String state) {
            return URI.create("https://provider.test/google?state=" + state);
        }

        @Override
        public OAuthUserProfile fetchProfile(String code) {
            return new OAuthUserProfile(
                    OAuthProvider.GOOGLE,
                    "google-web-1",
                    "web-oauth@example.com",
                    "웹사용자",
                    "provider-token"
            );
        }
    }
}
