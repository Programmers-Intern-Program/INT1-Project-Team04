package com.back.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Config: CORS 설정 테스트")
class CorsConfigTest extends IntegrationTestBase {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("frontend origin에서 credentials 포함 API preflight를 허용한다")
    void allowsFrontendOriginPreflight() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/auth/me"))
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
                .contains("GET");
    }
}
