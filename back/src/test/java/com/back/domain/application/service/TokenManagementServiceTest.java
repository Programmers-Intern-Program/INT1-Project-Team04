package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.command.GrantTokenCommand;
import com.back.domain.application.command.UseTokenCommand;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.LoadUserTokenPort;
import com.back.domain.application.port.out.SaveTokenUsageHistoryPort;
import com.back.domain.application.port.out.SaveUserTokenPort;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenResult;
import com.back.domain.model.token.TokenUsageHistory;
import com.back.domain.model.token.TokenUsageType;
import com.back.domain.model.token.UserToken;
import com.back.domain.model.user.User;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 토큰 관리 서비스 테스트")
class TokenManagementServiceTest {

    private TokenManagementService service;
    private FakeUserPort fakeUserPort;
    private FakeUserTokenPort fakeUserTokenPort;
    private FakeTokenUsageHistoryPort fakeTokenUsageHistoryPort;

    @BeforeEach
    void setUp() {
        fakeUserPort = new FakeUserPort();
        fakeUserTokenPort = new FakeUserTokenPort();
        fakeTokenUsageHistoryPort = new FakeTokenUsageHistoryPort();

        service = new TokenManagementService(
                fakeUserPort,
                fakeUserTokenPort,
                fakeUserTokenPort,
                fakeTokenUsageHistoryPort
        );
    }

    @Test
    @DisplayName("성공: 신규 사용자의 토큰 잔액 조회 시 초기 토큰 생성")
    void createsInitialTokenForNewUser() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        // When
        UserTokenResult result = service.getBalance(1L);

        // Then
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.balance()).isEqualTo(0);
        assertThat(result.totalGranted()).isEqualTo(0);
        assertThat(result.totalUsed()).isEqualTo(0);
    }

    @Test
    @DisplayName("성공: 기존 사용자의 토큰 잔액 조회")
    void getsExistingUserBalance() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        UserToken token = new UserToken("token-1", user, 100, 100, 0, LocalDateTime.now(), LocalDateTime.now());
        fakeUserTokenPort.save(token);

        // When
        UserTokenResult result = service.getBalance(1L);

        // Then
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.balance()).isEqualTo(100);
        assertThat(result.totalGranted()).isEqualTo(100);
        assertThat(result.totalUsed()).isEqualTo(0);
    }

    @Test
    @DisplayName("실패: 존재하지 않는 사용자의 토큰 잔액 조회")
    void failsToGetBalanceForNonExistentUser() {
        // When & Then
        assertThatThrownBy(() -> service.getBalance(999L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("성공: 토큰 사용 (차감)")
    void usesToken() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        UserToken token = new UserToken("token-1", user, 100, 100, 0, LocalDateTime.now(), LocalDateTime.now());
        fakeUserTokenPort.save(token);

        UseTokenCommand command = new UseTokenCommand(1L, 10, "테스트 사용", "ref-123");

        // When
        UserTokenResult result = service.useToken(command);

        // Then
        assertThat(result.balance()).isEqualTo(90);
        assertThat(result.totalUsed()).isEqualTo(10);
        assertThat(fakeTokenUsageHistoryPort.saved).hasSize(1);

        TokenUsageHistory history = fakeTokenUsageHistoryPort.saved.get(0);
        assertThat(history.type()).isEqualTo(TokenUsageType.USE);
        assertThat(history.amount()).isEqualTo(10);
        assertThat(history.balanceBefore()).isEqualTo(100);
        assertThat(history.balanceAfter()).isEqualTo(90);
        assertThat(history.description()).isEqualTo("테스트 사용");
        assertThat(history.referenceId()).isEqualTo("ref-123");
    }

    @Test
    @DisplayName("실패: 토큰 부족으로 사용 실패")
    void failsToUseTokenDueToInsufficientBalance() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        UserToken token = new UserToken("token-1", user, 5, 100, 95, LocalDateTime.now(), LocalDateTime.now());
        fakeUserTokenPort.save(token);

        UseTokenCommand command = new UseTokenCommand(1L, 10, "테스트 사용", null);

        // When & Then
        assertThatThrownBy(() -> service.useToken(command))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_TOKEN);

        // 토큰이 차감되지 않았는지 확인
        assertThat(fakeUserTokenPort.loadByUserId(1L).get().balance()).isEqualTo(5);
        assertThat(fakeTokenUsageHistoryPort.saved).isEmpty();
    }

    @Test
    @DisplayName("실패: 0 이하의 금액 사용 시도")
    void failsToUseTokenWithInvalidAmount() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        UserToken token = new UserToken("token-1", user, 100, 100, 0, LocalDateTime.now(), LocalDateTime.now());
        fakeUserTokenPort.save(token);

        UseTokenCommand command = new UseTokenCommand(1L, 0, "테스트 사용", null);

        // When & Then
        assertThatThrownBy(() -> service.useToken(command))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN_AMOUNT);
    }

    @Test
    @DisplayName("실패: 토큰 정보가 없는 사용자의 토큰 사용 시도")
    void failsToUseTokenForUserWithoutTokenInfo() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        UseTokenCommand command = new UseTokenCommand(1L, 10, "테스트 사용", null);

        // When & Then
        assertThatThrownBy(() -> service.useToken(command))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_NOT_FOUND);
    }

    @Test
    @DisplayName("성공: 토큰 부여 (충전)")
    void grantsToken() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        UserToken token = new UserToken("token-1", user, 50, 100, 50, LocalDateTime.now(), LocalDateTime.now());
        fakeUserTokenPort.save(token);

        GrantTokenCommand command = new GrantTokenCommand(1L, 100, "월간 토큰 지급");

        // When
        UserTokenResult result = service.grantToken(command);

        // Then
        assertThat(result.balance()).isEqualTo(150);
        assertThat(result.totalGranted()).isEqualTo(200);
        assertThat(result.totalUsed()).isEqualTo(50);
        assertThat(fakeTokenUsageHistoryPort.saved).hasSize(1);

        TokenUsageHistory history = fakeTokenUsageHistoryPort.saved.get(0);
        assertThat(history.type()).isEqualTo(TokenUsageType.GRANT);
        assertThat(history.amount()).isEqualTo(100);
        assertThat(history.balanceBefore()).isEqualTo(50);
        assertThat(history.balanceAfter()).isEqualTo(150);
        assertThat(history.description()).isEqualTo("월간 토큰 지급");
    }

    @Test
    @DisplayName("성공: 신규 사용자에게 토큰 부여 시 초기 토큰 생성")
    void grantsTokenToNewUser() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        GrantTokenCommand command = new GrantTokenCommand(1L, 100, "가입 축하 토큰");

        // When
        UserTokenResult result = service.grantToken(command);

        // Then
        assertThat(result.balance()).isEqualTo(100);
        assertThat(result.totalGranted()).isEqualTo(100);
        assertThat(result.totalUsed()).isEqualTo(0);
    }

    @Test
    @DisplayName("실패: 0 이하의 금액 부여 시도")
    void failsToGrantTokenWithInvalidAmount() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        GrantTokenCommand command = new GrantTokenCommand(1L, -10, "잘못된 금액");

        // When & Then
        assertThatThrownBy(() -> service.grantToken(command))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN_AMOUNT);
    }

    @Test
    @DisplayName("성공: 토큰 사용 내역 조회")
    void getsUsageHistory() {
        // Given
        User user = new User(1L, "test@example.com", "Test User", null, LocalDateTime.now());
        fakeUserPort.save(user);

        fakeTokenUsageHistoryPort.saved.add(
                new TokenUsageHistory("hist-1", user, TokenUsageType.GRANT, 100, 0, 100, "초기 지급", null, LocalDateTime.now())
        );
        fakeTokenUsageHistoryPort.saved.add(
                new TokenUsageHistory("hist-2", user, TokenUsageType.USE, 10, 100, 90, "AI 파싱", "ref-1", LocalDateTime.now())
        );

        // When
        List<TokenUsageHistoryResult> results = service.getUsageHistory(1L, 10);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).usageType()).isEqualTo(TokenUsageType.GRANT);
        assertThat(results.get(1).usageType()).isEqualTo(TokenUsageType.USE);
    }

    // Fake Implementations

    static class FakeUserPort implements LoadUserPort {
        private final List<User> users = new ArrayList<>();

        void save(User user) {
            users.add(user);
        }

        @Override
        public Optional<User> loadById(Long userId) {
            return users.stream()
                    .filter(u -> u.id().equals(userId))
                    .findFirst();
        }
    }

    static class FakeUserTokenPort implements LoadUserTokenPort, SaveUserTokenPort {
        private final List<UserToken> tokens = new ArrayList<>();

        @Override
        public Optional<UserToken> loadByUserId(Long userId) {
            return tokens.stream()
                    .filter(t -> t.user().id().equals(userId))
                    .findFirst();
        }

        @Override
        public UserToken save(UserToken userToken) {
            tokens.removeIf(t -> t.user().id().equals(userToken.user().id()));
            tokens.add(userToken);
            return userToken;
        }
    }

    static class FakeTokenUsageHistoryPort implements SaveTokenUsageHistoryPort {
        private final List<TokenUsageHistory> saved = new ArrayList<>();

        @Override
        public TokenUsageHistory save(TokenUsageHistory history) {
            saved.add(history);
            return history;
        }

        @Override
        public List<TokenUsageHistory> findByUserId(Long userId, int limit) {
            return saved.stream()
                    .filter(h -> h.user().id().equals(userId))
                    .limit(limit)
                    .toList();
        }
    }
}
