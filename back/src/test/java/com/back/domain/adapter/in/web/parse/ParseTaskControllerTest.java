package com.back.domain.adapter.in.web.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.session.ParseSessionJpaRepository;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Web: 자연어 파싱 API 테스트")
class ParseTaskControllerTest extends IntegrationTestBase {

    @Autowired
    private ParseSessionJpaRepository parseSessionJpaRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("성공: 자연어 입력을 파싱하고 결과를 반환한다")
    void parsesNaturalLanguageInput() throws Exception {
        // Given
        String requestBody = """
                {
                  "userId": 1,
                  "input": "강남 집값 5퍼센트 오르면 알려줘"
                }
                """;

        // When
        HttpResponse<String> response = postParse(requestBody);

        // Then
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"sessionId\"");
        assertThat(response.body()).contains("\"tasks\"");
        assertThat(response.body()).contains("\"isComplete\"");
        
        // TODO: AI 연동 후 실제 파싱 결과 검증
        // assertThat(response.body()).contains("\"domainName\":\"부동산\"");
    }

    @Test
    @DisplayName("실패: userId가 없으면 400 에러를 반환한다")
    void returnsErrorWhenUserIdIsMissing() throws Exception {
        // Given
        String requestBody = """
                {
                  "input": "강남 집값 5퍼센트 오르면 알려줘"
                }
                """;

        // When
        HttpResponse<String> response = postParse(requestBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @Test
    @DisplayName("실패: input이 비어있으면 400 에러를 반환한다")
    void returnsErrorWhenInputIsBlank() throws Exception {
        // Given
        String requestBody = """
                {
                  "userId": 1,
                  "input": ""
                }
                """;

        // When
        HttpResponse<String> response = postParse(requestBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(response.body()).contains("input");
    }

    @Test
    @DisplayName("성공: 후속 대화로 모호한 정보를 보완한다")
    void continuesParseConversation() throws Exception {
        // Given - 1턴: 초기 파싱
        String initialRequest = """
                {
                  "userId": 1,
                  "input": "집값 알려줘"
                }
                """;

        HttpResponse<String> initialResponse = postParse(initialRequest);
        assertThat(initialResponse.statusCode()).isEqualTo(200);

        // sessionId 추출 (간단한 파싱)
        String responseBody = initialResponse.body();
        String sessionId = responseBody.substring(
            responseBody.indexOf("\"sessionId\":\"") + 14,
            responseBody.indexOf("\"", responseBody.indexOf("\"sessionId\":\"") + 14)
        );

        // 2턴: 후속 응답
        String continueRequest = """
                {
                  "userId": 1,
                  "response": "강남, 5퍼센트 오르면"
                }
                """.formatted();

        // When
        HttpResponse<String> continueResponse = postContinueParse(sessionId, continueRequest);

        // Then
        assertThat(continueResponse.statusCode()).as(continueResponse.body()).isEqualTo(200);
        assertThat(continueResponse.body()).contains("\"sessionId\":\"" + sessionId + "\"");
        assertThat(continueResponse.body()).contains("\"tasks\"");
        
        // TODO: AI 연동 후 실제 업데이트 결과 검증
    }

    @Test
    @DisplayName("실패: 존재하지 않는 세션으로 후속 파싱 시 404 에러")
    void returnsErrorWhenSessionNotFound() throws Exception {
        // Given
        String requestBody = """
                {
                  "userId": 1,
                  "response": "강남"
                }
                """;

        // When
        HttpResponse<String> response = postContinueParse("non-existent-session-id", requestBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"SESSION_NOT_FOUND\"");
    }

    private HttpResponse<String> postParse(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/parse"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postContinueParse(String sessionId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/parse/continue/" + sessionId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
