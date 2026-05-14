package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.domain.application.result.ParsedTask;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * [Outgoing Adapter] Vertex AI (Gemini) 기반 자연어 파싱 어댑터.
 *
 * Spring AI ChatClient를 사용해 ParseNaturalLanguagePort를 구현한다.
 * 모델/리전 설정은 application-{profile}.yml의 spring.ai.vertex.ai.gemini.* 에서 관리.
 *
 * 이전 구현(GlmTaskParserAdapter)은 @Profile("glm")으로 비활성화되어 있으며,
 * RestClient + GLM API 직접 호출 방식의 이력 보존용으로 남겨 두었다.
 */
@Slf4j
@Profile("vertex")
@Component
public class VertexAiTaskParserAdapter implements ParseNaturalLanguagePort {

    private static final long PARSE_TIMEOUT_SECONDS = 300;

    // parserChatClient: MCP tool 없는 순수 ChatClient (AiConfig 참조)
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public VertexAiTaskParserAdapter(
            @Qualifier("parserChatClient") ChatClient chatClient,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 사용자의 자연어 입력을 처음 파싱할 때 호출.
     * system: 전체 파싱 규칙 (PromptTemplate.SYSTEM_PROMPT)
     * user: "사용자 요청: {input}"
     */
    @Override
    public List<ParsedTask> parse(String userInput) {
        String raw = callWithTimeout(
                () -> chatClient.prompt()
                        .system(PromptTemplate.SYSTEM_PROMPT)
                        .user(PromptTemplate.buildUserPrompt(userInput))
                        .call()
                        .content(),
                "parse",
                userInput
        );
        return parseTasks(raw);
    }

    /**
     * 멀티턴 대화에서 사용자의 후속 답변을 받아 파싱 결과를 업데이트할 때 호출.
     *
     * history는 ParseSession에 쌓인 role/content 쌍의 목록이다.
     *   - "user"    → UserMessage
     *   - "assistant" → AssistantMessage (이전 파싱 결과 JSON + confirmation_question)
     *
     * system: 후속 대화 전용 규칙 (PromptTemplate.CONTINUE_SYSTEM_PROMPT)
     * messages: 변환된 대화 이력 전체
     */
    @Override
    public List<ParsedTask> continueParse(List<ConversationMessage> history) {
        List<Message> messages = history.stream()
                .map(m -> switch (m.role()) {
                    case "assistant" -> (Message) new AssistantMessage(m.content());
                    // "user" 및 예상치 못한 role은 UserMessage로 처리
                    default -> (Message) new UserMessage(m.content());
                })
                .toList();

        String raw = callWithTimeout(
                () -> chatClient.prompt()
                        .system(PromptTemplate.CONTINUE_SYSTEM_PROMPT)
                        .messages(messages)
                        .call()
                        .content(),
                "continueParse",
                "history size: " + history.size()
        );
        return parseTasks(raw);
    }

    private String callWithTimeout(java.util.concurrent.Callable<String> call, String op, String context) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return call.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).orTimeout(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("Vertex AI {} 타임아웃 ({}s) - {}", op, PARSE_TIMEOUT_SECONDS, context);
            } else {
                log.error("Vertex AI {} 호출 실패 - {}", op, context, cause);
            }
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    // ── 이하 JSON 파싱 로직 ────────────────────────────────────────────────

    private List<ParsedTask> parseTasks(String raw) {
        String json = extractJson(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                List<ParsedTask> tasks = new ArrayList<>();
                for (JsonNode node : root) {
                    tasks.add(parseSingleTask(node));
                }
                return tasks;
            }
            // 단일 객체로 반환된 경우 배열로 감쌈
            return List.of(parseSingleTask(root));
        } catch (Exception e) {
            log.error("AI 응답 JSON 파싱 실패. 원본: {}", raw, e);
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private ParsedTask parseSingleTask(JsonNode node) {
        // 필수 필드 및 enum 값 검증 (AiParsedTaskSchema.validate 내부에서 ApiException throw)
        AiParsedTaskSchema.validate(node);

        JsonNode meta = node.path("metadata");
        return new ParsedTask(
                node.path("intent").asText(""),
                node.path("domain_name").asText(""),
                node.path("query").asText(""),
                node.path("condition").asText(""),
                node.path("cron_expr").asText(""),
                node.path("channel").asText(""),
                node.path("api_type").asText(""),
                meta.path("target").asText(""),
                toStringList(meta.path("urls")),
                meta.path("confidence").asDouble(0.5),
                meta.path("needs_confirmation").asBoolean(false),
                meta.path("confirmation_question").asText("")
        );
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }

    /**
     * AI 응답에서 JSON 부분만 추출한다.
     * Gemini가 마크다운 코드 블록(```json ... ```)으로 감싸서 반환하는 경우를 처리.
     */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";

        // 마크다운 코드 블록 제거
        String cleaned = raw.replaceAll("```(?:json)?\\s*", "").replaceAll("\\s*```", "").trim();
        if (cleaned.isEmpty()) return "[]";

        // 이미 유효한 JSON이면 그대로 반환
        try {
            objectMapper.readTree(cleaned);
            return cleaned;
        } catch (Exception ignored) {}

        // 배열([)이 객체({)보다 앞에 있으면 배열 추출 우선
        int bracketPos = cleaned.indexOf('[');
        int bracePos = cleaned.indexOf('{');
        if (bracketPos != -1 && (bracePos == -1 || bracketPos < bracePos)) {
            Matcher m = Pattern.compile("\\[.*]", Pattern.DOTALL).matcher(cleaned);
            if (m.find()) return m.group();
        }

        // 단일 객체 추출 후 배열로 감쌈
        Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(cleaned);
        if (m.find()) return "[" + m.group() + "]";

        log.warn("JSON 추출 실패 - 원본 앞 300자: {}", raw.substring(0, Math.min(300, raw.length())));
        return "[]";
    }
}
