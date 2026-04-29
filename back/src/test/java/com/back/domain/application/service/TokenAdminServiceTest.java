package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.token.TokenUsageHistoryPersistenceAdapter;
import com.back.domain.application.port.out.LoadTokenStatisticsPort;
import com.back.domain.application.result.TokenStatisticsResult;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenSummaryResult;
import com.back.domain.model.token.TokenUsageHistory;
import com.back.domain.model.token.TokenUsageType;
import com.back.domain.model.token.UserToken;
import com.back.domain.model.user.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 토큰 관리자 서비스 테스트")
class TokenAdminServiceTest {

    private TokenAdminService service;
    private FakeLoadTokenStatisticsPort fakeLoadTokenStatisticsPort;
    private FakeTokenUsageHistoryAdapter fakeHistoryAdapter;

    @BeforeEach
    void setUp() {
        fakeLoadTokenStatisticsPort = new FakeLoadTokenStatisticsPort();
        fakeHistoryAdapter = new FakeTokenUsageHistoryAdapter();
        service = new TokenAdminService(fakeLoadTokenStatisticsPort, fakeHistoryAdapter);
    }

    @Test
    @DisplayName("전체 토큰 통계를 조회한다")
    void getsStatistics() {
        // Given
        fakeLoadTokenStatisticsPort.totalUserCount = 10;
        fakeLoadTokenStatisticsPort.totalGranted = 1000;
        fakeLoadTokenStatisticsPort.totalUsed = 300;
        fakeLoadTokenStatisticsPort.totalBalance = 700;
        fakeLoadTokenStatisticsPort.activeUserCount = 8;

        // When
        TokenStatisticsResult result = service.getStatistics();

        // Then
        assertThat(result.totalUsers()).isEqualTo(10);
        assertThat(result.totalTokensGranted()).isEqualTo(1000);
        assertThat(result.totalTokensUsed()).isEqualTo(300);
        assertThat(result.totalTokensRemaining()).isEqualTo(700);
        assertThat(result.activeUsers()).isEqualTo(8);
    }

    @Test
    @DisplayName("통계 조회 시 데이터가 없으면 0을 반환한다")
    void getsStatisticsWithNoData() {
        // Given (모든 값이 0)

        // When
        TokenStatisticsResult result = service.getStatistics();

        // Then
        assertThat(result.totalUsers()).isEqualTo(0);
        assertThat(result.totalTokensGranted()).isEqualTo(0);
        assertThat(result.totalTokensUsed()).isEqualTo(0);
        assertThat(result.totalTokensRemaining()).isEqualTo(0);
        assertThat(result.activeUsers()).isEqualTo(0);
    }

    @Test
    @DisplayName("전체 사용자 토큰 현황을 페이징 조회한다")
    void getsAllUserTokens() {
        // Given
        User user1 = new User(1L, "user1@example.com", "사용자1", LocalDateTime.now().minusDays(30), null);
        User user2 = new User(2L, "user2@example.com", "사용자2", LocalDateTime.now().minusDays(20), null);

        UserToken token1 = new UserToken(
                "token-1", user1, 100, 150, 50,
                LocalDateTime.now(), LocalDateTime.now().minusDays(10)
        );
        UserToken token2 = new UserToken(
                "token-2", user2, 200, 200, 0,
                LocalDateTime.now(), LocalDateTime.now().minusDays(5)
        );

        fakeLoadTokenStatisticsPort.allUserTokens = List.of(token1, token2);

        // When
        List<UserTokenSummaryResult> results = service.getAllUserTokens(0, 20);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).userId()).isEqualTo(1L);
        assertThat(results.get(0).userEmail()).isEqualTo("user1@example.com");
        assertThat(results.get(0).balance()).isEqualTo(100);
        assertThat(results.get(0).totalGranted()).isEqualTo(150);
        assertThat(results.get(0).totalUsed()).isEqualTo(50);

        assertThat(results.get(1).userId()).isEqualTo(2L);
        assertThat(results.get(1).userEmail()).isEqualTo("user2@example.com");
        assertThat(results.get(1).balance()).isEqualTo(200);
    }

    @Test
    @DisplayName("사용자가 없으면 빈 목록을 반환한다")
    void getsAllUserTokensWithNoUsers() {
        // Given (빈 목록)

        // When
        List<UserTokenSummaryResult> results = service.getAllUserTokens(0, 20);

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("최근 토큰 사용 내역을 조회한다")
    void getsRecentHistory() {
        // Given
        User user1 = new User(1L, "user1@example.com", "사용자1", LocalDateTime.now().minusDays(30), null);
        User user2 = new User(2L, "user2@example.com", "사용자2", LocalDateTime.now().minusDays(20), null);

        TokenUsageHistory history1 = new TokenUsageHistory(
                "hist-1", user1, TokenUsageType.USE, 10, 100, 90,
                "AI 파싱", "session-1", LocalDateTime.now().minusHours(1)
        );
        TokenUsageHistory history2 = new TokenUsageHistory(
                "hist-2", user2, TokenUsageType.GRANT, 100, 0, 100,
                "웰컴 토큰", null, LocalDateTime.now().minusDays(1)
        );

        fakeHistoryAdapter.recentHistory = List.of(history1, history2);

        // When
        List<TokenUsageHistoryResult> results = service.getRecentHistory(100);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("hist-1");
        assertThat(results.get(0).userId()).isEqualTo(1L);
        assertThat(results.get(0).userEmail()).isEqualTo("user1@example.com");
        assertThat(results.get(0).usageType()).isEqualTo(TokenUsageType.USE);
        assertThat(results.get(0).amount()).isEqualTo(10);
        assertThat(results.get(0).sessionId()).isEqualTo("session-1");

        assertThat(results.get(1).id()).isEqualTo("hist-2");
        assertThat(results.get(1).userId()).isEqualTo(2L);
        assertThat(results.get(1).usageType()).isEqualTo(TokenUsageType.GRANT);
    }

    @Test
    @DisplayName("사용 내역이 없으면 빈 목록을 반환한다")
    void getsRecentHistoryWithNoData() {
        // Given (빈 목록)

        // When
        List<TokenUsageHistoryResult> results = service.getRecentHistory(100);

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("페이징 파라미터가 정확히 전달된다")
    void passesCorrectPagingParameters() {
        // Given
        int expectedPage = 2;
        int expectedSize = 50;

        // When
        service.getAllUserTokens(expectedPage, expectedSize);

        // Then
        assertThat(fakeLoadTokenStatisticsPort.lastPage).isEqualTo(expectedPage);
        assertThat(fakeLoadTokenStatisticsPort.lastSize).isEqualTo(expectedSize);
    }

    @Test
    @DisplayName("최근 내역 조회 시 limit이 정확히 전달된다")
    void passesCorrectLimitParameter() {
        // Given
        int expectedLimit = 50;

        // When
        service.getRecentHistory(expectedLimit);

        // Then
        assertThat(fakeHistoryAdapter.lastLimit).isEqualTo(expectedLimit);
    }

    // Fake Implementations

    static class FakeLoadTokenStatisticsPort implements LoadTokenStatisticsPort {
        List<UserToken> allUserTokens = new ArrayList<>();
        long totalGranted = 0;
        long totalUsed = 0;
        long totalBalance = 0;
        long activeUserCount = 0;
        long totalUserCount = 0;
        
        int lastPage = -1;
        int lastSize = -1;

        @Override
        public List<UserToken> loadAllUserTokens(int page, int size) {
            lastPage = page;
            lastSize = size;
            return allUserTokens;
        }

        @Override
        public long getTotalGranted() {
            return totalGranted;
        }

        @Override
        public long getTotalUsed() {
            return totalUsed;
        }

        @Override
        public long getTotalBalance() {
            return totalBalance;
        }

        @Override
        public long getActiveUserCount() {
            return activeUserCount;
        }

        @Override
        public long getTotalUserCount() {
            return totalUserCount;
        }
    }

    static class FakeTokenUsageHistoryAdapter extends TokenUsageHistoryPersistenceAdapter {
        List<TokenUsageHistory> recentHistory = new ArrayList<>();
        int lastLimit = -1;

        public FakeTokenUsageHistoryAdapter() {
            super(null, null);
        }

        @Override
        public List<TokenUsageHistory> findRecentHistory(int limit) {
            lastLimit = limit;
            return recentHistory;
        }
    }
}
