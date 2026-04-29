package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.domain.application.port.out.ParseNaturalLanguagePort.ConversationMessage;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.model.session.ParseSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

@Tag("ai-manual")
@DisplayName("AI: 멀티턴 파싱 수동 테스트")
@EnabledIf("isAiTestEnabled")
class MultiTurnParseManualTest {

    @SuppressWarnings("unused")
    static boolean isAiTestEnabled() {
        return Boolean.parseBoolean(System.getProperty("ai.test.enabled", "false"));
    }

    private final ParseNaturalLanguagePort parser = createAdapter();

    private static ParseNaturalLanguagePort createAdapter() {
        String baseUrl = System.getProperty("ai-gateway.base-url",
                System.getenv().getOrDefault("AI_BASE_URL",
                        System.getenv().getOrDefault("AI_PARSER_BASE_URL", "")));
        String apiKey = System.getProperty("ai-gateway.api-key",
                System.getenv().getOrDefault("AI_API_KEY",
                        System.getenv().getOrDefault("AI_PARSER_API_KEY", "")));

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "AI_BASE_URL, AI_API_KEY를 시스템 프로퍼티 또는 환경변수로 설정하세요.\n"
                    + "실행 예: ./gradlew aiTest -DAI.test.enabled=true "
                    + "-Dai-gateway.base-url=URL -Dai-gateway.api-key=KEY"
            );
        }

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json");

        return new GlmTaskParserAdapter(builder, baseUrl, apiKey, new ObjectMapper());
    }

    @Test
    @DisplayName("AI: 멀티턴 - 모호한 입력 → 추가 답변 → 명확한 결과")
    void multiTurnFromAmbiguousToClear() {
        // 1턴: 모호한 입력
        String firstInput = "집값 알려줘";
        List<ParsedTask> firstResult = parser.parse(firstInput);
        printTurn(1, firstInput, firstResult);

        assertThat(firstResult).isNotEmpty();

        ParseSession session = new ParseSession("test-session-1", 1L, firstInput, firstResult);
        String question = session.getFirstConfirmationQuestion();
        System.out.println(">>> AI 재질문: " + question);

        // 2턴: 추가 답변
        String userResponse = "강남구 아파트, 5% 이상 오르면 알려줘";
        session.addMessage("assistant", question != null ? question : firstResult.get(0).confirmationQuestion());
        session.addMessage("user", userResponse);
        session.incrementTurn();

        List<ConversationMessage> history = new ArrayList<>(session.getMessages().stream()
                .map(m -> new ConversationMessage(m.role(), m.content()))
                .toList());

        List<ParsedTask> secondResult = parser.continueParse(history);
        session.updateResult(secondResult);
        printTurn(2, userResponse, secondResult);

        System.out.println(">>> 세션 완료 여부: " + session.isComplete());
        System.out.println(">>> 최종 confidence: " + secondResult.get(0).confidence());
    }

    @Test
    @DisplayName("AI: 멀티턴 - 삭제 요청 후 대상 특정")
    void multiTurnDeleteRequest() {
        // 1턴: 삭제 요청
        String firstInput = "알림 취소해줘";
        List<ParsedTask> firstResult = parser.parse(firstInput);
        printTurn(1, firstInput, firstResult);

        assertThat(firstResult).isNotEmpty();
        assertThat(firstResult.get(0).intent()).isEqualTo("delete");

        ParseSession session = new ParseSession("test-session-2", 1L, firstInput, firstResult);

        // 2턴: 어떤 알림인지 특정
        String userResponse = "강남 집값 알림 취소해줘";
        String question = session.getFirstConfirmationQuestion();
        if (question != null) {
            session.addMessage("assistant", question);
        }
        session.addMessage("user", userResponse);
        session.incrementTurn();

        List<ConversationMessage> history = new ArrayList<>(session.getMessages().stream()
                .map(m -> new ConversationMessage(m.role(), m.content()))
                .toList());

        List<ParsedTask> secondResult = parser.continueParse(history);
        session.updateResult(secondResult);
        printTurn(2, userResponse, secondResult);

        System.out.println(">>> 세션 완료 여부: " + session.isComplete());
    }

    @Test
    @DisplayName("AI: 멀티턴 - max turns 초과 시 강제 완료")
    void multiTurnForceCompleteOnMaxTurns() {
        // 1턴: 모호한 입력
        String firstInput = "알림 바꿔줘";
        List<ParsedTask> firstResult = parser.parse(firstInput);
        printTurn(1, firstInput, firstResult);

        ParseSession session = new ParseSession("test-session-3", 1L, firstInput, firstResult);

        // 2턴
        String secondResponse = "조건이요";
        addTurn(session, secondResponse);
        List<ParsedTask> secondResult = callContinueParse(session);
        session.updateResult(secondResult);
        printTurn(2, secondResponse, secondResult);

        // 3턴
        String thirdResponse = "10%로";
        addTurn(session, thirdResponse);
        List<ParsedTask> thirdResult = callContinueParse(session);
        session.updateResult(thirdResult);
        printTurn(3, thirdResponse, thirdResult);

        // max turns 초과 체크
        session.incrementTurn();
        if (session.isMaxTurnsExceeded()) {
            System.out.println(">>> max turns 초과! 강제 완료 처리");
            session.forceComplete();
        }

        System.out.println(">>> 최종 완료 여부: " + session.isComplete());
        System.out.println(">>> 최종 태스크 수: " + session.getCurrentResult().size());
        session.getCurrentResult().forEach(t ->
                System.out.println(">>> needs_confirmation: " + t.needsConfirmation()));
        assertThat(session.isComplete()).isTrue();
    }

    private void addTurn(ParseSession session, String userResponse) {
        String question = session.getFirstConfirmationQuestion();
        if (question != null) {
            session.addMessage("assistant", question);
        }
        session.addMessage("user", userResponse);
        session.incrementTurn();
    }

    private List<ParsedTask> callContinueParse(ParseSession session) {
        List<ConversationMessage> history = new ArrayList<>(session.getMessages().stream()
                .map(m -> new ConversationMessage(m.role(), m.content()))
                .toList());
        return parser.continueParse(history);
    }

    private void printTurn(int turn, String input, List<ParsedTask> tasks) {
        System.out.println("\n========== TURN " + turn + " ==========");
        System.out.println("입력: " + input);
        System.out.println("파싱 결과: " + tasks.size() + "개 태스크");
        for (int i = 0; i < tasks.size(); i++) {
            ParsedTask t = tasks.get(i);
            System.out.println("  [" + (i + 1) + "]");
            System.out.println("    intent:              " + t.intent());
            System.out.println("    domain_name:         " + t.domainName());
            System.out.println("    query:               " + t.query());
            System.out.println("    condition:           " + t.condition());
            System.out.println("    cron_expr:           " + t.cronExpr());
            System.out.println("    channel:             " + t.channel());
            System.out.println("    api_type:            " + t.apiType());
            System.out.println("    target:              " + t.target());
            System.out.println("    confidence:          " + t.confidence());
            System.out.println("    needs_confirmation:  " + t.needsConfirmation());
            System.out.println("    confirmation_question: " + t.confirmationQuestion());
        }
        System.out.println("================================\n");
    }
}
