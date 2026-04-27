package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.domain.application.result.ParsedTask;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class GlmTaskParserAdapter implements ParseNaturalLanguagePort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${ai-parser.model:glm-4.5}")
    private String model;

    @Value("${ai-parser.max-tokens:2048}")
    private int maxTokens;

    @Value("${ai-parser.temperature:0.3}")
    private double temperature;

    public GlmTaskParserAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${ai-parser.base-url}") String baseUrl,
            @Value("${ai-parser.api-key}") String apiKey,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ParsedTask> parse(String userInput) {
        List<GlmApiDto.Message> messages = List.of(
                new GlmApiDto.Message("system", PromptTemplate.SYSTEM_PROMPT),
                new GlmApiDto.Message("user", PromptTemplate.buildUserPrompt(userInput))
        );
        String raw = callApi(messages);
        return parseTasks(raw);
    }

    @Override
    public List<ParsedTask> continueParse(List<ConversationMessage> history) {
        List<GlmApiDto.Message> messages = new ArrayList<>();
        messages.add(new GlmApiDto.Message("system", PromptTemplate.CONTINUE_SYSTEM_PROMPT));
        for (ConversationMessage msg : history) {
            messages.add(new GlmApiDto.Message(msg.role(), msg.content()));
        }
        String raw = callApi(messages);
        return parseTasks(raw);
    }

    private String callApi(List<GlmApiDto.Message> messages) {
        GlmApiDto.Request request = new GlmApiDto.Request(model, messages, maxTokens, temperature);
        try {
            String responseBody = restClient.post()
                    .body(request)
                    .retrieve()
                    .body(String.class);
            if (responseBody == null) {
                throw new ApiException(ErrorCode.AI_PARSE_FAILED);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.at("/choices/0/message/content");
            if (contentNode.isMissingNode() || contentNode.asText().isEmpty()) {
                throw new ApiException(ErrorCode.AI_PARSE_FAILED);
            }
            return contentNode.asText().trim();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("GLM API 호출 실패", e);
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

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
            return List.of(parseSingleTask(root));
        } catch (Exception e) {
            log.error("JSON 파싱 실패. 원본: {}", raw, e);
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private ParsedTask parseSingleTask(JsonNode node) {
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

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";
        // Remove code blocks
        String cleaned = raw.replaceAll("```(?:json)?\\s*", "").replaceAll("\\s*```", "").trim();
        if (cleaned.isEmpty()) return "[]";
        // Try parsing as-is
        try {
            objectMapper.readTree(cleaned);
            return cleaned;
        } catch (Exception ignored) {}
        // Try extracting array
        int bracketPos = cleaned.indexOf('[');
        int bracePos = cleaned.indexOf('{');
        if (bracketPos != -1 && (bracePos == -1 || bracketPos < bracePos)) {
            Matcher m = Pattern.compile("\\[.*]", Pattern.DOTALL).matcher(cleaned);
            if (m.find()) return m.group();
        }
        // Try extracting object and wrap in array
        Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(cleaned);
        if (m.find()) return "[" + m.group() + "]";
        log.warn("JSON 추출 실패: {}", raw.substring(0, Math.min(300, raw.length())));
        return "[]";
    }
}