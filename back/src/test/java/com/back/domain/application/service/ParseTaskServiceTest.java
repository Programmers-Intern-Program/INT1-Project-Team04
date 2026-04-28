package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.command.ContinueParseCommand;
import com.back.domain.application.command.ParseTaskCommand;
import com.back.domain.application.port.out.LoadParseSessionPort;
import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.domain.application.port.out.SaveParseSessionPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.model.session.ParseSession;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 자연어 파싱 서비스 테스트")
class ParseTaskServiceTest {

    private ParseTaskService parseTaskService;
    private FakeParseNaturalLanguagePort parseNaturalLanguagePort;
    private FakeSaveParseSessionPort saveParseSessionPort;
    private FakeLoadParseSessionPort loadParseSessionPort;

    @BeforeEach
    void setUp() {
        parseNaturalLanguagePort = new FakeParseNaturalLanguagePort();
        saveParseSessionPort = new FakeSaveParseSessionPort();
        loadParseSessionPort = new FakeLoadParseSessionPort();

        parseTaskService = new ParseTaskService(
            parseNaturalLanguagePort,
            saveParseSessionPort,
            loadParseSessionPort,
            new ObjectMapper()
        );
    }

    @Test
    @DisplayName("성공: 명확한 자연어 입력을 파싱하여 완료 상태로 저장한다")
    void parsesClearNaturalLanguageInput() {
        // Given - 명확한 입력
        ParsedTask task = new ParsedTask(
            "create", "부동산", "강남 아파트 가격", "5% 이상 상승",
            "0 9 * * *", "discord", "crawl", "강남 지역 아파트 시세",
            List.of(), 0.85, false, ""
        );
        parseNaturalLanguagePort.setParseResult(List.of(task));

        ParseTaskCommand command = new ParseTaskCommand(1L, "강남 집값 5% 오르면 알려줘");

        // When
        ParseResult result = parseTaskService.parse(command);

        // Then
        assertThat(result.sessionId()).isNotNull();
        assertThat(result.tasks()).hasSize(1);
        
        ParsedTask resultTask = result.tasks().get(0);
        assertThat(resultTask.domainName()).isEqualTo("부동산");
        assertThat(resultTask.query()).isEqualTo("강남 아파트 가격");
        assertThat(resultTask.condition()).isEqualTo("5% 이상 상승");
        assertThat(resultTask.needsConfirmation()).isFalse();

        // 세션이 저장되었는지 확인
        assertThat(saveParseSessionPort.savedSession).isNotNull();
        assertThat(saveParseSessionPort.savedSession.getUserId()).isEqualTo(1L);
        assertThat(saveParseSessionPort.savedSession.getOriginalInput()).isEqualTo("강남 집값 5% 오르면 알려줘");
        assertThat(saveParseSessionPort.savedSession.isComplete()).isTrue();
    }

    @Test
    @DisplayName("성공: 모호한 입력을 파싱하여 확인 필요 상태로 저장한다")
    void parsesAmbiguousInput() {
        // Given - 모호한 입력
        ParsedTask task = new ParsedTask(
            "create", "부동산", "집값", "",
            "0 9 * * *", "discord", "crawl", "아파트 가격",
            List.of(), 0.4, true, "어느 지역의 어떤 조건으로 알려드릴까요?"
        );
        parseNaturalLanguagePort.setParseResult(List.of(task));

        ParseTaskCommand command = new ParseTaskCommand(1L, "집값 알려줘");

        // When
        ParseResult result = parseTaskService.parse(command);

        // Then
        assertThat(result.tasks()).hasSize(1);
        ParsedTask resultTask = result.tasks().get(0);
        assertThat(resultTask.needsConfirmation()).isTrue();
        assertThat(resultTask.confirmationQuestion()).isEqualTo("어느 지역의 어떤 조건으로 알려드릴까요?");

        assertThat(saveParseSessionPort.savedSession.isComplete()).isFalse();
    }

    @Test
    @DisplayName("성공: 후속 대화로 모호한 정보를 보완하여 완료한다")
    void continuesParseToCompleteAmbiguousInput() {
        // Given - 1턴: 모호한 입력으로 세션 생성
        ParsedTask initialTask = new ParsedTask(
            "create", "부동산", "집값", "",
            "0 9 * * *", "discord", "crawl", "아파트 가격",
            List.of(), 0.4, true, "어느 지역의 어떤 조건으로 알려드릴까요?"
        );
        parseNaturalLanguagePort.setParseResult(List.of(initialTask));

        ParseTaskCommand parseCommand = new ParseTaskCommand(1L, "집값 알려줘");
        ParseResult firstResult = parseTaskService.parse(parseCommand);

        // 세션을 로드 가능하게 설정
        loadParseSessionPort.setSession(saveParseSessionPort.savedSession);

        // 2턴: 정보 보완
        ParsedTask updatedTask = new ParsedTask(
            "create", "부동산", "강남 아파트 가격", "5% 이상 상승",
            "0 9 * * *", "discord", "crawl", "강남 지역 아파트 시세",
            List.of(), 0.85, false, ""
        );
        parseNaturalLanguagePort.setContinueParseResult(List.of(updatedTask));

        ContinueParseCommand continueCommand = new ContinueParseCommand(
            1L, firstResult.sessionId(), "강남, 5% 오르면"
        );

        // When
        ParseResult finalResult = parseTaskService.continueParse(continueCommand);

        // Then
        assertThat(finalResult.sessionId()).isEqualTo(firstResult.sessionId());
        assertThat(finalResult.tasks()).hasSize(1);
        
        ParsedTask finalTask = finalResult.tasks().get(0);
        assertThat(finalTask.query()).isEqualTo("강남 아파트 가격");
        assertThat(finalTask.condition()).isEqualTo("5% 이상 상승");
        assertThat(finalTask.needsConfirmation()).isFalse();

        assertThat(saveParseSessionPort.savedSession.isComplete()).isTrue();
    }

    @Test
    @DisplayName("성공: 후속 파싱에 assistant 질문과 이전 JSON 컨텍스트를 포함한다")
    void continueParseIncludesAssistantQuestionAndPreviousJsonContext() {
        ParsedTask initialTask = new ParsedTask(
            "create", "부동산", "집값", "",
            "0 9 * * *", "discord", "crawl", "아파트 가격",
            List.of(), 0.4, true, "어느 지역의 어떤 조건으로 알려드릴까요?"
        );
        parseNaturalLanguagePort.setParseResult(List.of(initialTask));
        ParseResult firstResult = parseTaskService.parse(new ParseTaskCommand(1L, "집값 알려줘"));
        loadParseSessionPort.setSession(saveParseSessionPort.savedSession);
        parseNaturalLanguagePort.setContinueParseResult(List.of(new ParsedTask(
            "create", "부동산", "강남 아파트 가격", "5% 이상 상승",
            "0 9 * * *", "discord", "crawl", "강남 지역 아파트 시세",
            List.of(), 0.85, false, ""
        )));

        parseTaskService.continueParse(new ContinueParseCommand(
            1L, firstResult.sessionId(), "강남, 5% 오르면"
        ));

        assertThat(parseNaturalLanguagePort.lastContinueHistory)
            .extracting(ParseNaturalLanguagePort.ConversationMessage::role)
            .containsExactly("user", "assistant", "assistant", "user");
        assertThat(parseNaturalLanguagePort.lastContinueHistory.get(1).content())
            .contains("현재 파싱 결과 JSON")
            .contains("\"query\":\"집값\"");
        assertThat(parseNaturalLanguagePort.lastContinueHistory.get(2).content())
            .isEqualTo("어느 지역의 어떤 조건으로 알려드릴까요?");
    }

    @Test
    @DisplayName("실패: 존재하지 않는 세션으로 후속 파싱 시 예외 발생")
    void throwsExceptionWhenSessionNotFound() {
        // Given
        loadParseSessionPort.setSession(null); // 세션 없음

        ContinueParseCommand command = new ContinueParseCommand(
            1L, "non-existent-session", "강남"
        );

        // When & Then
        assertThatThrownBy(() -> parseTaskService.continueParse(command))
            .isInstanceOf(ApiException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("실패: 다른 사용자의 세션으로 후속 파싱 시 예외 발생")
    void throwsExceptionWhenUserIdDoesNotMatch() {
        // Given - userId=1 세션 생성
        ParsedTask task = new ParsedTask(
            "create", "부동산", "집값", "",
            "0 9 * * *", "discord", "crawl", "아파트",
            List.of(), 0.4, true, "지역?"
        );
        parseNaturalLanguagePort.setParseResult(List.of(task));

        ParseResult firstResult = parseTaskService.parse(new ParseTaskCommand(1L, "집값"));
        loadParseSessionPort.setSession(saveParseSessionPort.savedSession);

        // userId=2로 접근 시도
        ContinueParseCommand command = new ContinueParseCommand(
            2L, firstResult.sessionId(), "강남"
        );

        // When & Then
        assertThatThrownBy(() -> parseTaskService.continueParse(command))
            .isInstanceOf(ApiException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("성공: 이미 완료된 세션을 다시 요청하면 기존 결과 반환")
    void returnsExistingResultForCompletedSession() {
        // Given - 완료된 세션
        ParsedTask task = new ParsedTask(
            "create", "부동산", "강남 아파트", "5% 상승",
            "0 9 * * *", "discord", "crawl", "강남 아파트",
            List.of(), 0.9, false, ""
        );
        parseNaturalLanguagePort.setParseResult(List.of(task));

        ParseResult firstResult = parseTaskService.parse(new ParseTaskCommand(1L, "강남 집값 5% 오르면"));
        loadParseSessionPort.setSession(saveParseSessionPort.savedSession);

        ContinueParseCommand command = new ContinueParseCommand(
            1L, firstResult.sessionId(), "추가 응답"
        );

        // When
        ParseResult result = parseTaskService.continueParse(command);

        // Then - 기존 결과 그대로 반환
        assertThat(result.sessionId()).isEqualTo(firstResult.sessionId());
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).query()).isEqualTo("강남 아파트");
    }

    @Test
    @DisplayName("성공: 여러 태스크를 포함한 입력을 파싱한다")
    void parsesMultipleTasks() {
        // Given
        List<ParsedTask> tasks = List.of(
            new ParsedTask("create", "부동산", "강남 아파트", "10% 상승",
                "0 9 * * *", "discord", "crawl", "강남", List.of(), 0.9, false, ""),
            new ParsedTask("create", "채용", "백엔드 개발자", "신입",
                "0 9 * * 1", "discord", "crawl", "백엔드", List.of(), 0.8, false, "")
        );
        parseNaturalLanguagePort.setParseResult(tasks);

        ParseTaskCommand command = new ParseTaskCommand(
            1L, "강남 집값 10% 오르면 알려주고, 백엔드 신입 채용 있으면 알려줘"
        );

        // When
        ParseResult result = parseTaskService.parse(command);

        // Then
        assertThat(result.tasks()).hasSize(2);
        assertThat(result.tasks().get(0).domainName()).isEqualTo("부동산");
        assertThat(result.tasks().get(1).domainName()).isEqualTo("채용");
    }

    // Fake 구현체들
    private static class FakeParseNaturalLanguagePort implements ParseNaturalLanguagePort {
        private List<ParsedTask> parseResult;
        private List<ParsedTask> continueParseResult;
        private List<ConversationMessage> lastContinueHistory = List.of();

        void setParseResult(List<ParsedTask> tasks) {
            this.parseResult = tasks;
        }

        void setContinueParseResult(List<ParsedTask> tasks) {
            this.continueParseResult = tasks;
        }

        @Override
        public List<ParsedTask> parse(String userInput) {
            return parseResult;
        }

        @Override
        public List<ParsedTask> continueParse(List<ConversationMessage> history) {
            this.lastContinueHistory = List.copyOf(history);
            return continueParseResult;
        }
    }

    private static class FakeSaveParseSessionPort implements SaveParseSessionPort {
        private ParseSession savedSession;

        @Override
        public ParseSession save(ParseSession session) {
            this.savedSession = session;
            return session;
        }
    }

    private static class FakeLoadParseSessionPort implements LoadParseSessionPort {
        private ParseSession session;

        void setSession(ParseSession session) {
            this.session = session;
        }

        @Override
        public Optional<ParseSession> loadById(String sessionId) {
            return Optional.ofNullable(session);
        }
    }
}
