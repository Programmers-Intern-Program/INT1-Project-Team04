package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.command.ContinueParseCommand;
import com.back.domain.application.command.GrantTokenCommand;
import com.back.domain.application.command.ParseTaskCommand;
import com.back.domain.application.command.UseTokenCommand;
import com.back.domain.application.port.in.TokenManagementUseCase;
import com.back.domain.application.port.out.LoadParseSessionPort;
import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.domain.application.port.out.SaveParseSessionPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenResult;
import com.back.domain.model.session.ParseSession;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: ParseTaskService 토큰 차감 테스트")
class ParseTaskServiceWithTokenTest {

    private ParseTaskService service;
    private FakeParseNaturalLanguagePort fakeParsePort;
    private FakeSaveParseSessionPort fakeSaveSessionPort;
    private FakeLoadParseSessionPort fakeLoadSessionPort;
    private FakeTokenManagementUseCase fakeTokenManagement;

    @BeforeEach
    void setUp() {
        fakeParsePort = new FakeParseNaturalLanguagePort();
        fakeSaveSessionPort = new FakeSaveParseSessionPort();
        fakeLoadSessionPort = new FakeLoadParseSessionPort();
        fakeTokenManagement = new FakeTokenManagementUseCase();

        service = new ParseTaskService(
                fakeParsePort,
                fakeSaveSessionPort,
                fakeLoadSessionPort,
                fakeTokenManagement
        );
    }

    @Test
    @DisplayName("성공: 초기 파싱 시 10 토큰 차감")
    void deducts10TokensForInitialParse() {
        // Given
        Long userId = 1L;
        fakeTokenManagement.grantToken(new GrantTokenCommand(userId, 100, "초기 토큰"));

        ParsedTask task = new ParsedTask(
                "create", "부동산", "강남 아파트", "5% 상승", "0 9 * * *",
                "discord", "mcp", "realtor-api", List.of(), 0.9, false, ""
        );
        fakeParsePort.setParseResult(List.of(task));

        ParseTaskCommand command = new ParseTaskCommand(userId, "강남 집값 알려줘");

        // When
        ParseResult result = service.parse(command);

        // Then
        assertThat(result).isNotNull();
        assertThat(fakeTokenManagement.tokenUsed).containsEntry(userId, 10);

        UserTokenResult balance = fakeTokenManagement.getBalance(userId);
        assertThat(balance.balance()).isEqualTo(90);
        assertThat(balance.totalUsed()).isEqualTo(10);
    }

    @Test
    @DisplayName("실패: 토큰 부족으로 초기 파싱 실패")
    void failsToParseWhenInsufficientTokens() {
        // Given
        Long userId = 1L;
        fakeTokenManagement.grantToken(new GrantTokenCommand(userId, 5, "초기 토큰"));

        ParseTaskCommand command = new ParseTaskCommand(userId, "강남 집값 알려줘");

        // When & Then
        assertThatThrownBy(() -> service.parse(command))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_TOKEN);

        // AI 파싱이 호출되지 않았는지 확인
        assertThat(fakeParsePort.parseCalled).isFalse();

        // 토큰이 차감되지 않았는지 확인
        UserTokenResult balance = fakeTokenManagement.getBalance(userId);
        assertThat(balance.balance()).isEqualTo(5);
    }

    @Test
    @DisplayName("성공: 후속 파싱 시 5 토큰 차감")
    void deducts5TokensForContinueParse() {
        // Given
        Long userId = 1L;
        String sessionId = "session-123";

        fakeTokenManagement.grantToken(new GrantTokenCommand(userId, 100, "초기 토큰"));

        ParsedTask ambiguousTask = new ParsedTask(
                "create", "부동산", "", "", "",
                "", "", "", List.of(), 0.3, true, "어느 지역인가요?"
        );

        ParseSession session = new ParseSession(sessionId, userId, "집값 알려줘", List.of(ambiguousTask));
        fakeLoadSessionPort.save(session);

        ParsedTask clarifiedTask = new ParsedTask(
                "create", "부동산", "강남 아파트", "5% 상승", "0 9 * * *",
                "discord", "mcp", "realtor-api", List.of(), 0.9, false, ""
        );
        fakeParsePort.setContinueParseResult(List.of(clarifiedTask));

        ContinueParseCommand command = new ContinueParseCommand(userId, sessionId, "강남, 5% 오르면");

        // When
        ParseResult result = service.continueParse(command);

        // Then
        assertThat(result).isNotNull();
        assertThat(fakeTokenManagement.tokenUsed).containsEntry(userId, 5);

        UserTokenResult balance = fakeTokenManagement.getBalance(userId);
        assertThat(balance.balance()).isEqualTo(95);
        assertThat(balance.totalUsed()).isEqualTo(5);
    }

    @Test
    @DisplayName("실패: 토큰 부족으로 후속 파싱 실패")
    void failsToContinueParseWhenInsufficientTokens() {
        // Given
        Long userId = 1L;
        String sessionId = "session-123";

        fakeTokenManagement.grantToken(new GrantTokenCommand(userId, 3, "초기 토큰"));

        ParsedTask ambiguousTask = new ParsedTask(
                "create", "부동산", "", "", "",
                "", "", "", List.of(), 0.3, true, "어느 지역인가요?"
        );

        ParseSession session = new ParseSession(sessionId, userId, "집값 알려줘", List.of(ambiguousTask));
        fakeLoadSessionPort.save(session);

        ContinueParseCommand command = new ContinueParseCommand(userId, sessionId, "강남");

        // When & Then
        assertThatThrownBy(() -> service.continueParse(command))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_TOKEN);

        // AI 파싱이 호출되지 않았는지 확인
        assertThat(fakeParsePort.continueParseCalled).isFalse();

        // 토큰이 차감되지 않았는지 확인
        UserTokenResult balance = fakeTokenManagement.getBalance(userId);
        assertThat(balance.balance()).isEqualTo(3);
    }

    @Test
    @DisplayName("성공: 여러 번의 파싱으로 누적 토큰 차감")
    void deductsTokensForMultipleParsing() {
        // Given
        Long userId = 1L;
        fakeTokenManagement.grantToken(new GrantTokenCommand(userId, 100, "초기 토큰"));

        ParsedTask task = new ParsedTask(
                "create", "부동산", "강남 아파트", "5% 상승", "0 9 * * *",
                "discord", "mcp", "realtor-api", List.of(), 0.9, false, ""
        );
        fakeParsePort.setParseResult(List.of(task));

        // When - 3번의 초기 파싱
        service.parse(new ParseTaskCommand(userId, "강남 집값 알려줘"));
        service.parse(new ParseTaskCommand(userId, "서초 전세 알려줘"));
        service.parse(new ParseTaskCommand(userId, "용산 매매 알려줘"));

        // Then
        UserTokenResult balance = fakeTokenManagement.getBalance(userId);
        assertThat(balance.balance()).isEqualTo(70); // 100 - 10*3
        assertThat(balance.totalUsed()).isEqualTo(30);
    }

    // Fake Implementations

    static class FakeParseNaturalLanguagePort implements ParseNaturalLanguagePort {
        boolean parseCalled = false;
        boolean continueParseCalled = false;
        private List<ParsedTask> parseResult = new ArrayList<>();
        private List<ParsedTask> continueParseResult = new ArrayList<>();

        void setParseResult(List<ParsedTask> result) {
            this.parseResult = result;
        }

        void setContinueParseResult(List<ParsedTask> result) {
            this.continueParseResult = result;
        }

        @Override
        public List<ParsedTask> parse(String userInput) {
            parseCalled = true;
            return parseResult;
        }

        @Override
        public List<ParsedTask> continueParse(List<ConversationMessage> history) {
            continueParseCalled = true;
            return continueParseResult;
        }
    }

    static class FakeSaveParseSessionPort implements SaveParseSessionPort {
        private final Map<String, ParseSession> sessions = new HashMap<>();

        @Override
        public ParseSession save(ParseSession session) {
            sessions.put(session.getId(), session);
            return session;
        }
    }

    static class FakeLoadParseSessionPort implements LoadParseSessionPort {
        private final Map<String, ParseSession> sessions = new HashMap<>();

        void save(ParseSession session) {
            sessions.put(session.getId(), session);
        }

        @Override
        public Optional<ParseSession> loadById(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }
    }

    static class FakeTokenManagementUseCase implements TokenManagementUseCase {
        private final Map<Long, Integer> balances = new HashMap<>();
        private final Map<Long, Integer> totalGranted = new HashMap<>();
        private final Map<Long, Integer> totalUsed = new HashMap<>();
        final Map<Long, Integer> tokenUsed = new HashMap<>();

        @Override
        public UserTokenResult getBalance(Long userId) {
            return new UserTokenResult(
                    userId,
                    balances.getOrDefault(userId, 0),
                    totalGranted.getOrDefault(userId, 0),
                    totalUsed.getOrDefault(userId, 0),
                    LocalDateTime.now()
            );
        }

        @Override
        public UserTokenResult useToken(UseTokenCommand command) {
            int currentBalance = balances.getOrDefault(command.userId(), 0);
            if (currentBalance < command.amount()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_TOKEN);
            }

            balances.put(command.userId(), currentBalance - command.amount());
            totalUsed.merge(command.userId(), command.amount(), Integer::sum);
            tokenUsed.put(command.userId(), command.amount());

            return getBalance(command.userId());
        }

        @Override
        public UserTokenResult grantToken(GrantTokenCommand command) {
            balances.merge(command.userId(), command.amount(), Integer::sum);
            totalGranted.merge(command.userId(), command.amount(), Integer::sum);
            return getBalance(command.userId());
        }

        @Override
        public List<TokenUsageHistoryResult> getUsageHistory(Long userId, int limit) {
            return List.of();
        }
    }
}
