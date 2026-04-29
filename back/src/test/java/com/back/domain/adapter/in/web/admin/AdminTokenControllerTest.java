package com.back.domain.adapter.in.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.support.IntegrationTestBase;
import com.back.domain.adapter.out.persistence.token.TokenUsageHistoryJpaEntity;
import com.back.domain.adapter.out.persistence.token.TokenUsageHistoryJpaRepository;
import com.back.domain.adapter.out.persistence.token.UserTokenJpaEntity;
import com.back.domain.adapter.out.persistence.token.UserTokenJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.model.token.TokenUsageType;
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

@DisplayName("Web: 관리자 토큰 대시보드 API 테스트")
class AdminTokenControllerTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private UserTokenJpaRepository userTokenRepository;

    @Autowired
    private TokenUsageHistoryJpaRepository historyRepository;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private UserJpaEntity testUser1;
    private UserJpaEntity testUser2;

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        userTokenRepository.deleteAll();
        userRepository.deleteAll();

        testUser1 = userRepository.save(new UserJpaEntity("admin1@example.com", "관리자1"));
        testUser2 = userRepository.save(new UserJpaEntity("admin2@example.com", "관리자2"));

        var token1 = new UserTokenJpaEntity(
                "token-1",
                testUser1,
                100,
                150,
                50,
                LocalDateTime.now(),
                LocalDateTime.now().minusDays(10)
        );
        userTokenRepository.save(token1);

        var token2 = new UserTokenJpaEntity(
                "token-2",
                testUser2,
                200,
                200,
                0,
                LocalDateTime.now(),
                LocalDateTime.now().minusDays(5)
        );
        userTokenRepository.save(token2);

        var history1 = new TokenUsageHistoryJpaEntity(
                "history-1",
                testUser1,
                TokenUsageType.USE,
                10,
                110,
                100,
                "AI 자연어 파싱",
                "session-1",
                LocalDateTime.now().minusHours(1)
        );
        historyRepository.save(history1);

        var history2 = new TokenUsageHistoryJpaEntity(
                "history-2",
                testUser2,
                TokenUsageType.GRANT,
                200,
                0,
                200,
                "신규 가입 웰컴 토큰",
                null,
                LocalDateTime.now().minusDays(5)
        );
        historyRepository.save(history2);
    }

    @Test
    @DisplayName("전체 토큰 통계를 조회한다")
    void getsStatistics() throws Exception {
        // When
        HttpResponse<String> response = requestGetStatistics();
        
        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("totalUsers").asLong()).isEqualTo(2);
        assertThat(json.get("totalTokensGranted").asLong()).isEqualTo(350);
        assertThat(json.get("totalTokensUsed").asLong()).isEqualTo(50);
        assertThat(json.get("totalTokensRemaining").asLong()).isEqualTo(300);
        assertThat(json.get("activeUsers").asLong()).isEqualTo(2);
        assertThat(json.get("averageTokensPerUser").asDouble()).isEqualTo(175.0);
    }

    @Test
    @DisplayName("전체 사용자의 토큰 현황을 페이징 조회한다")
    void getsAllUserTokens() throws Exception {
        // When
        HttpResponse<String> response = requestGetAllUserTokens(0, 10);
        
        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("users").isArray()).isTrue();
        assertThat(json.get("users").size()).isEqualTo(2);
        assertThat(json.get("page").asInt()).isEqualTo(0);
        assertThat(json.get("size").asInt()).isEqualTo(10);
        assertThat(json.get("totalElements").asInt()).isEqualTo(2);
        assertThat(json.get("users").get(0).get("email")).isNotNull();
        assertThat(json.get("users").get(0).get("balance")).isNotNull();
    }

    @Test
    @DisplayName("최근 토큰 사용 내역을 조회한다")
    void getsRecentHistory() throws Exception {
        // When
        HttpResponse<String> response = requestGetRecentHistory(50);
        
        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("history").isArray()).isTrue();
        assertThat(json.get("history").size()).isEqualTo(2);
        assertThat(json.get("totalCount").asInt()).isEqualTo(2);
        assertThat(json.get("history").get(0).get("userId")).isNotNull();
        assertThat(json.get("history").get(0).get("userEmail")).isNotNull();
        assertThat(json.get("history").get(0).get("usageType")).isNotNull();
    }

    @Test
    @DisplayName("사용자 토큰 현황을 페이지 크기를 지정하여 조회한다")
    void getsAllUserTokensWithCustomPageSize() throws Exception {
        // When
        HttpResponse<String> response = requestGetAllUserTokens(0, 1);
        
        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("users").size()).isEqualTo(1);
        assertThat(json.get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("최근 토큰 사용 내역을 제한된 개수만큼 조회한다")
    void getsRecentHistoryWithLimit() throws Exception {
        // When
        HttpResponse<String> response = requestGetRecentHistory(1);
        
        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("history").size()).isEqualTo(1);
        assertThat(json.get("totalCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("통계에서 활성 사용자는 잔액이 0보다 큰 사용자만 포함한다")
    void statisticsActiveUsersOnlyIncludesUsersWithPositiveBalance() throws Exception {
        // Given
        var userWithZeroBalance = userRepository.save(new UserJpaEntity("zero@example.com", "잔액0"));
        var tokenZero = new UserTokenJpaEntity(
                "token-zero",
                userWithZeroBalance,
                0,
                100,
                100,
                LocalDateTime.now(),
                LocalDateTime.now().minusDays(1)
        );
        userTokenRepository.save(tokenZero);

        // When
        HttpResponse<String> response = requestGetStatistics();
        
        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("totalUsers").asLong()).isEqualTo(3);
        assertThat(json.get("activeUsers").asLong()).isEqualTo(2);
    }
    
    // Helper Methods
    
    private HttpResponse<String> requestGetStatistics() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/admin/tokens/statistics"))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
    
    private HttpResponse<String> requestGetAllUserTokens(int page, int size) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/admin/tokens/users?page=" + page + "&size=" + size))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
    
    private HttpResponse<String> requestGetRecentHistory(int limit) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/admin/tokens/history/recent?limit=" + limit))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
