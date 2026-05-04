package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.NormalizeSubscriptionDraftPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.service.subscriptionconversation.DomainNormalizedSubscriptionDraft;
import com.back.domain.application.service.subscriptionconversation.SubscriptionDraft;
import com.back.domain.model.mcp.McpTool;
import com.back.global.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * [Infrastructure Adapter] MCP 서버의 normalize_subscription_draft 도구를 호출하는 어댑터
 * * Spring AI MCP client 실행 포트(ExecuteMcpToolPort)를 재사용해 실제 MCP 서버와 통신한다.
 * * 백엔드 ParsedTask/SubscriptionDraft 형태를 MCP 입력 스키마(userMessage, task, previousDraft)로 변환한다.
 * * MCP 응답의 공통 래퍼({structured, source_url, metadata}) 중 structured 값만
 * Application 계층의 DomainNormalizedSubscriptionDraft 로 복원한다.
 * * 정규화 실패는 Optional.empty()로 반환해 ParsedTaskNormalizer 가 MCP_REQUEST_FAILED 로 드러내게 한다.
 *   백엔드 로컬 파싱 fallback 은 사용하지 않는다.
 */
@Component
public class McpSubscriptionDraftNormalizerAdapter implements NormalizeSubscriptionDraftPort {

    private static final McpTool NORMALIZE_TOOL = new McpTool(
            null,
            null,
            null,
            "normalize_subscription_draft",
            "구독 초안 구조화",
            "{}"
    );

    private final ExecuteMcpToolPort executeMcpToolPort;
    private final ObjectMapper objectMapper;

    public McpSubscriptionDraftNormalizerAdapter(
            ExecuteMcpToolPort executeMcpToolPort,
            ObjectMapper objectMapper
    ) {
        this.executeMcpToolPort = executeMcpToolPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DomainNormalizedSubscriptionDraft> normalize(
            ParsedTask task,
            String userMessage,
            SubscriptionDraft previousDraft
    ) {
        try {
            McpExecutionResult result = executeMcpToolPort.execute(
                    NORMALIZE_TOOL,
                    Map.of("input", input(task, userMessage, previousDraft))
            );
            return parse(result.metadata());
        } catch (ApiException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    // FastMCP 도구 입력은 항상 {"input": {...}} 래퍼를 사용한다.
    // 내부 필드명은 Python Pydantic 모델의 validation_alias 와 맞춰 camelCase 로 전달한다.
    private Map<String, Object> input(ParsedTask task, String userMessage, SubscriptionDraft previousDraft) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("userMessage", userMessage);
        input.put("task", task(task));
        if (previousDraft != null) {
            input.put("previousDraft", previousDraft(previousDraft));
        }
        return input;
    }

    // AI parser 가 만든 ParsedTask 전체를 MCP 로 넘긴다.
    // MCP 쪽에서 domain/intent/query/condition 을 실제 도구 계약으로 다시 판단한다.
    private Map<String, Object> task(ParsedTask task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("intent", task.intent());
        values.put("domainName", task.domainName());
        values.put("query", task.query());
        values.put("condition", task.condition());
        values.put("cronExpr", task.cronExpr());
        values.put("channel", task.channel());
        values.put("apiType", task.apiType());
        values.put("target", task.target());
        values.put("urls", task.urls());
        values.put("confidence", task.confidence());
        values.put("needsConfirmation", task.needsConfirmation());
        values.put("confirmationQuestion", task.confirmationQuestion());
        return values;
    }

    // 멀티 턴 대화에서 앞 턴의 region/condition/채널 정보를 재사용할 수 있게 넘긴다.
    // 단, 최종 재사용 여부는 MCP 와 백엔드가 domainName 일치 여부를 기준으로 각각 판단한다.
    private Map<String, Object> previousDraft(SubscriptionDraft previousDraft) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("query", previousDraft.query());
        values.put("domainName", previousDraft.domainName());
        values.put("intent", previousDraft.intent());
        values.put("toolName", previousDraft.toolName());
        values.put("monitoringParams", previousDraft.monitoringParams());
        values.put("notificationChannel", previousDraft.notificationChannel());
        values.put("notificationTargetAddress", previousDraft.notificationTargetAddress());
        return values;
    }

    // McpHttpAdapter 는 MCP 공통 응답을 metadata JSON 안에 다시 감싸서 넘긴다.
    // 여기서는 그 중 structured 노드만 읽어 Application 전용 DTO 로 변환한다.
    private Optional<DomainNormalizedSubscriptionDraft> parse(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode structured = root.path("structured");
            if (!structured.isObject()) {
                return Optional.empty();
            }
            return Optional.of(new DomainNormalizedSubscriptionDraft(
                    text(structured, "query"),
                    text(structured, "domainName"),
                    text(structured, "intent"),
                    text(structured, "toolName"),
                    parameters(structured.path("parameters")),
                    strings(structured.path("missingFields")),
                    text(structured, "question"),
                    structured.path("confidence").asDouble(0)
            ));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private Map<String, String> parameters(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> parameters.put(entry.getKey(), entry.getValue().asText()));
        return parameters;
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
