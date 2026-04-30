package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.result.ParsedTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

@Tag("ai-manual")
@DisplayName("AI: GLM Task Parser 수동 테스트")
@EnabledIf("isAiTestEnabled")
class GlmTaskParserManualTest {

    @SuppressWarnings("unused")
    static boolean isAiTestEnabled() {
        return Boolean.parseBoolean(System.getProperty("ai.test.enabled", "false"));
    }

    private final GlmTaskParserAdapter adapter = createAdapter();

    private static GlmTaskParserAdapter createAdapter() {
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
    @DisplayName("AI: 명확한 create 요청 - 강남 아파트 가격 5% 상승 알림")
    void parseCreateRequest() {
        String input = "강남 아파트 가격 5% 이상 상승하면 디스코드로 알려줘";

        List<ParsedTask> tasks = adapter.parse(input);

        printResult("CREATE 요청", input, tasks);
        assertThat(tasks).isNotEmpty();
        tasks.forEach(t -> {
            assertThat(t.intent()).isNotEmpty();
            assertThat(t.domainName()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("AI: delete 요청 - 알림 취소")
    void parseDeleteRequest() {
        String input = "알림 취소해줘";

        List<ParsedTask> tasks = adapter.parse(input);

        printResult("DELETE 요청", input, tasks);
        assertThat(tasks).isNotEmpty();
        tasks.forEach(t -> assertThat(t.intent()).isEqualTo("delete"));
    }

    @Test
    @DisplayName("AI: modify 요청 - 조건 변경")
    void parseModifyRequest() {
        String input = "조건 10%로 바꿔줘";

        List<ParsedTask> tasks = adapter.parse(input);

        printResult("MODIFY 요청", input, tasks);
        assertThat(tasks).isNotEmpty();
        tasks.forEach(t -> assertThat(t.intent()).isEqualTo("modify"));
    }

    @Test
    @DisplayName("AI: reject - 미지원 도메인 (비트코인)")
    void parseRejectUnsupportedDomain() {
        String input = "비트코인 가격 알려줘";

        List<ParsedTask> tasks = adapter.parse(input);

        printResult("REJECT (미지원 도메인)", input, tasks);
        assertThat(tasks).isNotEmpty();
        tasks.forEach(t -> assertThat(t.intent()).isEqualTo("reject"));
    }

    @Test
    @DisplayName("AI: 모호한 요청 - confirmation 유도")
    void parseAmbiguousRequest() {
        String input = "집값 알려줘";

        List<ParsedTask> tasks = adapter.parse(input);

        printResult("AMBIGUOUS (모호한 요청)", input, tasks);
        assertThat(tasks).isNotEmpty();
        System.out.println(">>> needs_confirmation: " + tasks.get(0).needsConfirmation());
        System.out.println(">>> confirmation_question: " + tasks.get(0).confirmationQuestion());
    }

    @Test
    @DisplayName("AI: 복수 대상 - 강남 집값 + 수원 채용공고")
    void parseMultipleTargets() {
        String input = "강남 집값이랑 수원 채용공고 알려줘";

        List<ParsedTask> tasks = adapter.parse(input);

        printResult("MULTI-TARGET (복수 대상)", input, tasks);
        assertThat(tasks).hasSizeGreaterThanOrEqualTo(2);
    }

    private void printResult(String label, String input, List<ParsedTask> tasks) {
        System.out.println("\n========== " + label + " ==========");
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
            System.out.println("    urls:                " + t.urls());
            System.out.println("    confidence:          " + t.confidence());
            System.out.println("    needs_confirmation:  " + t.needsConfirmation());
            System.out.println("    confirmation_question: " + t.confirmationQuestion());
        }
        System.out.println("==========================================\n");
    }
}
