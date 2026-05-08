package com.back.domain.adapter.in.web.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.token.TokenUsageHistoryJpaRepository;
import com.back.domain.adapter.out.persistence.token.UserTokenJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Web: 토큰 관리 API 테스트")
class TokenControllerTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private UserTokenJpaRepository userTokenRepository;

    @Autowired
    private TokenUsageHistoryJpaRepository tokenUsageHistoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Long testUserId;

    @BeforeEach
    void setUp() {
        // 테스트용 사용자 생성
        UserJpaEntity user = new UserJpaEntity("test@example.com", "Test User");
        UserJpaEntity savedUser = userRepository.save(user);
        testUserId = savedUser.getId();
    }

    @Test
    @DisplayName("성공: 신규 사용자의 토큰 잔액 조회 (초기 토큰 생성)")
    void getsBalanceForNewUser() throws Exception {
        // When
        HttpResponse<String> response = getBalance(testUserId);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("userId").asLong()).isEqualTo(testUserId);
        assertThat(json.get("balance").asInt()).isEqualTo(0);
        assertThat(json.get("totalGranted").asInt()).isEqualTo(0);
        assertThat(json.get("totalUsed").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("성공: 토큰 부여 (충전)")
    void grantsToken() throws Exception {
        // Given
        String requestBody = """
                {
                  "userId": %d,
                  "amount": 100,
                  "description": "가입 축하 토큰"
                }
                """.formatted(testUserId);

        // When
        HttpResponse<String> response = postGrantToken(requestBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("userId").asLong()).isEqualTo(testUserId);
        assertThat(json.get("balance").asInt()).isEqualTo(100);
        assertThat(json.get("totalGranted").asInt()).isEqualTo(100);
        assertThat(json.get("totalUsed").asInt()).isEqualTo(0);

        // 사용 내역 확인
        assertThat(tokenUsageHistoryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("성공: 토큰 사용 (차감)")
    void usesToken() throws Exception {
        // Given - 먼저 토큰 부여
        String grantBody = """
                {
                  "userId": %d,
                  "amount": 100,
                  "description": "초기 토큰"
                }
                """.formatted(testUserId);
        postGrantToken(grantBody);

        String useBody = """
                {
                  "userId": %d,
                  "amount": 10,
                  "description": "AI 파싱",
                  "referenceId": "parse-session-123"
                }
                """.formatted(testUserId);

        // When
        HttpResponse<String> response = postUseToken(useBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("balance").asInt()).isEqualTo(90);
        assertThat(json.get("totalGranted").asInt()).isEqualTo(100);
        assertThat(json.get("totalUsed").asInt()).isEqualTo(10);

        // 사용 내역 확인 (GRANT 1건 + USE 1건)
        assertThat(tokenUsageHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패: 토큰 부족으로 사용 실패 (402 Payment Required)")
    void failsToUseTokenDueToInsufficientBalance() throws Exception {
        // Given - 10 토큰만 부여
        String grantBody = """
                {
                  "userId": %d,
                  "amount": 5,
                  "description": "초기 토큰"
                }
                """.formatted(testUserId);
        postGrantToken(grantBody);

        String useBody = """
                {
                  "userId": %d,
                  "amount": 10,
                  "description": "AI 파싱"
                }
                """.formatted(testUserId);

        // When
        HttpResponse<String> response = postUseToken(useBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(402);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("code").asText()).isEqualTo("INSUFFICIENT_TOKEN");
        assertThat(json.get("message").asText()).contains("토큰이 부족합니다");

        // 잔액이 변하지 않았는지 확인
        HttpResponse<String> balanceResponse = getBalance(testUserId);
        JsonNode balanceJson = objectMapper.readTree(balanceResponse.body());
        assertThat(balanceJson.get("balance").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("실패: 잘못된 금액으로 토큰 사용 (400 Bad Request)")
    void failsToUseTokenWithInvalidAmount() throws Exception {
        // Given
        String requestBody = """
                {
                  "userId": %d,
                  "amount": 0,
                  "description": "테스트"
                }
                """.formatted(testUserId);

        // When
        HttpResponse<String> response = postUseToken(requestBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("실패: userId 누락 시 400 에러")
    void failsToGrantTokenWithoutUserId() throws Exception {
        // Given
        String requestBody = """
                {
                  "amount": 100,
                  "description": "테스트"
                }
                """;

        // When
        HttpResponse<String> response = postGrantToken(requestBody);

        // Then
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("성공: 토큰 사용 내역 조회")
    void getsUsageHistory() throws Exception {
        // Given - 토큰 부여 및 사용
        String grantBody = """
                {
                  "userId": %d,
                  "amount": 100,
                  "description": "초기 토큰"
                }
                """.formatted(testUserId);
        postGrantToken(grantBody);

        String useBody = """
                {
                  "userId": %d,
                  "amount": 10,
                  "description": "AI 파싱"
                }
                """.formatted(testUserId);
        postUseToken(useBody);

        // When
        HttpResponse<String> response = getUsageHistory(testUserId, 10);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode history = json.get("history");
        assertThat(history.isArray()).isTrue();
        assertThat(history.size()).isEqualTo(2);

        // 최신순으로 정렬되어야 함 (USE가 먼저)
        assertThat(history.get(0).get("type").asText()).isEqualTo("USE");
        assertThat(history.get(0).get("amount").asInt()).isEqualTo(10);
        assertThat(history.get(0).get("balanceBefore").asInt()).isEqualTo(100);
        assertThat(history.get(0).get("balanceAfter").asInt()).isEqualTo(90);

        assertThat(history.get(1).get("type").asText()).isEqualTo("GRANT");
        assertThat(history.get(1).get("amount").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("성공: 토큰 사용 내역 조회 limit 테스트")
    void getsUsageHistoryWithLimit() throws Exception {
        // Given - 여러 건의 토큰 사용
        String grantBody = """
                {
                  "userId": %d,
                  "amount": 100,
                  "description": "초기 토큰"
                }
                """.formatted(testUserId);
        postGrantToken(grantBody);

        for (int i = 0; i < 5; i++) {
            String useBody = """
                    {
                      "userId": %d,
                      "amount": 5,
                      "description": "AI 파싱 %d"
                    }
                    """.formatted(testUserId, i);
            postUseToken(useBody);
        }

        // When - limit=3으로 조회
        HttpResponse<String> response = getUsageHistory(testUserId, 3);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode history = json.get("history");
        assertThat(history.size()).isEqualTo(3);
    }

    // Helper Methods

    private HttpResponse<String> getBalance(Long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/tokens/balance/" + userId))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postGrantToken(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/tokens/grant"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postUseToken(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/tokens/use"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getUsageHistory(Long userId, int limit) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/tokens/history/" + userId + "?limit=" + limit))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
