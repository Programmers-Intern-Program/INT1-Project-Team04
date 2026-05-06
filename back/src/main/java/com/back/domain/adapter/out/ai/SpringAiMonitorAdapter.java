package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.RunAiMonitorPort;
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.application.service.SubscriptionContext;
import com.back.domain.adapter.out.ai.McpToolExecutionRecorder.Execution;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpringAiMonitorAdapter implements RunAiMonitorPort, RunSubscriptionExecutionPort {

    private static final Set<String> DATA_TOOL_NAMES = Set.of(
            "get_cached_data",
            "search_house_price",
            "search_apt_rent",
            "search_offi_trade",
            "search_offi_rent",
            "search_rh_trade",
            "search_rh_rent",
            "search_law_info",
            "search_bill_info",
            "search_g2b_bid",
            "search_public_job",
            "search_worknet_job"
    );

    @Nullable
    private final ChatClient monitorChatClient;
    private final ObjectMapper objectMapper;

    // [레버 1] system prompt로 tool 호출 순서/조건 유도
    // [레버 2] MCP tool description에 순서/조건 명시 → Python 담당자 담당
    // 두 레버가 일치할수록 AI의 tool 선택이 안정적으로 동작함
    private static final String SYSTEM_PROMPT = """
            당신은 구독 모니터링 에이전트입니다.
            반드시 다음 순서로 tool을 사용하세요:
            1. 사전 check tool → 시장 이벤트/뉴스 확인
            2. 판단: 유의미한 변화가 있을 때만 fetch tool 호출
            3. 변화가 있으면 브리핑 생성 후 Discord 발송
            """;
    // TODO: tool 이름/단계별 조건은 Python 담당자와 합의 후 위 프롬프트에 반영

    public SpringAiMonitorAdapter(
            @Autowired(required = false) ChatClient monitorChatClient,
            ObjectMapper objectMapper
    ) {
        this.monitorChatClient = monitorChatClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run() {
        if (monitorChatClient == null) {
            log.warn("[SpringAiMonitorAdapter] ChatClient 미구성 — 스킵");
            return;
        }
        log.info("[SpringAiMonitorAdapter] AI 모니터링 트리거 전송");
        monitorChatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("구독 모니터링을 실행해주세요.")
                .call()
                .content();
    }

    // 구독 실행 흐름: 스케줄러가 due 구독 목록을 넘기면 MCP server에 위임
    @Override
    public void execute(List<SubscriptionContext> subscriptions) {
        if (monitorChatClient == null) {
            log.warn("[SpringAiMonitorAdapter] ChatClient 미구성 — 구독 실행 스킵 ({}건)", subscriptions.size());
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
        if (subscriptions.isEmpty()) {
            return;
        }
        log.info("[SpringAiMonitorAdapter] 구독 실행 요청 - {}건", subscriptions.size());
        try {
            String payload = objectMapper.writeValueAsString(subscriptions);
            ExecutionResult result = requestMonitorExecution(payload);
            String content = result.content();
            List<Execution> executions = result.executions();
            if (shouldRetryMissingCompare(content, subscriptions, executions)) {
                // 데이터 조회까지 성공한 뒤 멈춘 경우, 백엔드 계산 대신 MCP compare 호출만 한 번 더 유도한다.
                ExecutionResult retryResult = requestMonitorExecution(retryPrompt(payload, executions));
                executions = mergeExecutions(executions, retryResult.executions());
                verifyToolExecutionEvidence(subscriptions, executions);
                return;
            }
            verifyExecutionResponse(content, subscriptions, executions);
        } catch (JsonProcessingException e) {
            log.error("[SpringAiMonitorAdapter] 구독 context 직렬화 실패", e);
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private ExecutionResult requestMonitorExecution(String userPrompt) {
        // Gemini가 도구 호출 후 최종 text를 비우는 경우가 있어 MCP 콜백 실행 기록도 함께 본다.
        McpToolExecutionRecorder.start();
        String content;
        List<Execution> executions;
        try {
            content = monitorChatClient.prompt()
                    .system(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
        } finally {
            executions = McpToolExecutionRecorder.stop();
        }
        return new ExecutionResult(content, executions);
    }

    private boolean shouldRetryMissingCompare(
            String content,
            List<SubscriptionContext> subscriptions,
            List<Execution> executions
    ) {
        if (readResultsNode(content).isPresent() || !hasSuccessfulDataToolExecution(executions)) {
            return false;
        }
        return subscriptions.stream()
                .anyMatch(subscription -> compareExecutions(subscription, subscriptions, executions).isEmpty());
    }

    private String retryPrompt(String payload, List<Execution> executions) {
        return """
                이전 구독 실행에서 데이터 도구 호출 후 compare_subscription_change 호출이 누락되었습니다.
                원래 구독 JSON:
                %s

                이미 실행된 MCP 도구 기록:
                %s

                위 데이터 도구 응답을 current로 사용해 누락된 구독마다 compare_subscription_change를 호출하세요.
                데이터 도구와 check_api_cache는 다시 호출하지 마세요.
                compare 결과상 알림이 필요한 경우에만 send_notification을 호출하세요.
                최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(payload, executionEvidenceForRetry(executions));
    }

    private String executionEvidenceForRetry(List<Execution> executions) {
        List<Map<String, Object>> evidence = executions.stream()
                .filter(execution -> !execution.failed())
                .map(execution -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("toolName", execution.toolName());
                    item.put("input", execution.input());
                    item.put("output", execution.output());
                    return item;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            return evidence.toString();
        }
    }

    private List<Execution> mergeExecutions(List<Execution> first, List<Execution> second) {
        List<Execution> merged = new ArrayList<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    private void verifyExecutionResponse(
            String content,
            List<SubscriptionContext> subscriptions,
            List<Execution> executions
    ) {
        Optional<JsonNode> optionalResultsNode = readResultsNode(content);
        if (optionalResultsNode.isEmpty()) {
            verifyToolExecutionEvidence(subscriptions, executions);
            return;
        }

        JsonNode resultsNode = optionalResultsNode.get();
        Map<String, JsonNode> results = new HashMap<>();
        resultsNode.forEach(node -> {
            String subscriptionId = text(node, "subscriptionId", "subscription_id");
            if (subscriptionId != null && !subscriptionId.isBlank()) {
                results.put(subscriptionId, node);
            }
        });

        for (SubscriptionContext subscription : subscriptions) {
            JsonNode result = results.get(subscription.subscriptionId());
            if (result == null || !isSuccessful(result)) {
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
        }
    }

    private Optional<JsonNode> readResultsNode(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (String candidate : jsonCandidates(content)) {
            JsonNode root = readJson(candidate).orElse(null);
            if (root == null) {
                continue;
            }
            JsonNode resultsNode = root.isArray() ? root : root.path("results");
            if (resultsNode.isArray()) {
                return Optional.of(resultsNode);
            }
        }
        log.warn("[SpringAiMonitorAdapter] 실행 증빙 JSON 파싱 실패 - 응답 일부: {}", abbreviatedForLog(content));
        return Optional.empty();
    }

    private Optional<JsonNode> readJson(String candidate) {
        try {
            return Optional.of(objectMapper.readTree(candidate));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private List<String> jsonCandidates(String content) {
        Set<String> candidates = new LinkedHashSet<>();
        String trimmed = content.strip();
        candidates.add(trimmed);
        // 모델이 지시를 어기고 코드블록/설명문을 붙여도 내부 JSON 계약은 그대로 검증한다.
        fencedJson(trimmed).ifPresent(candidates::add);
        jsonEnvelope(trimmed).ifPresent(candidates::add);
        return new ArrayList<>(candidates);
    }

    private Optional<String> fencedJson(String content) {
        int fenceStart = content.indexOf("```");
        if (fenceStart < 0) {
            return Optional.empty();
        }
        int bodyStart = content.indexOf('\n', fenceStart + 3);
        int fenceEnd = bodyStart < 0 ? -1 : content.indexOf("```", bodyStart + 1);
        if (bodyStart < 0 || fenceEnd < 0) {
            return Optional.empty();
        }
        return Optional.of(content.substring(bodyStart + 1, fenceEnd).strip());
    }

    private Optional<String> jsonEnvelope(String content) {
        int objectStart = content.indexOf('{');
        int arrayStart = content.indexOf('[');
        int start = firstJsonStart(objectStart, arrayStart);
        if (start < 0) {
            return Optional.empty();
        }
        char close = content.charAt(start) == '{' ? '}' : ']';
        int end = content.lastIndexOf(close);
        if (end <= start) {
            return Optional.empty();
        }
        return Optional.of(content.substring(start, end + 1).strip());
    }

    private int firstJsonStart(int objectStart, int arrayStart) {
        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    private String abbreviatedForLog(String content) {
        String value = content
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<email>");
        int maxLength = 500;
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private void verifyToolExecutionEvidence(List<SubscriptionContext> subscriptions, List<Execution> executions) {
        if (executions.isEmpty()) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }

        for (SubscriptionContext subscription : subscriptions) {
            if (!hasSuccessfulDataToolExecution(executions)) {
                log.warn("[SpringAiMonitorAdapter] 데이터 도구 실행 증빙 없음 - tools={}", toolNames(executions));
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
            List<Execution> compareExecutions = compareExecutions(subscription, subscriptions, executions);
            if (compareExecutions.isEmpty()) {
                log.warn("[SpringAiMonitorAdapter] 비교 도구 실행 증빙 없음 - subscriptionId={}, tools={}",
                        subscription.subscriptionId(), toolNames(executions));
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
            boolean notificationRequired = notificationRequired(compareExecutions)
                    .orElseThrow(() -> new ApiException(ErrorCode.MCP_REQUEST_FAILED));
            if (notificationRequired && !hasSentNotification(subscription, subscriptions, executions)) {
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
        }
    }

    private List<String> toolNames(List<Execution> executions) {
        return executions.stream()
                .map(Execution::toolName)
                .toList();
    }

    private boolean hasSuccessfulDataToolExecution(List<Execution> executions) {
        return executions.stream()
                .anyMatch(execution -> !execution.failed() && DATA_TOOL_NAMES.stream()
                        .anyMatch(toolName -> isTool(execution, toolName)));
    }

    private List<Execution> compareExecutions(
            SubscriptionContext subscription,
            List<SubscriptionContext> subscriptions,
            List<Execution> executions
    ) {
        return executions.stream()
                .filter(execution -> !execution.failed())
                .filter(execution -> isTool(execution, "compare_subscription_change"))
                .filter(execution -> subscriptions.size() == 1 || mentionsSubscription(execution, subscription))
                .toList();
    }

    private Optional<Boolean> notificationRequired(List<Execution> compareExecutions) {
        for (Execution execution : compareExecutions) {
            Optional<JsonNode> structured = structuredNode(execution.output());
            if (structured.isEmpty()) {
                continue;
            }
            JsonNode node = structured.get();
            if (bool(node, "baseline_initialized", "baselineInitialized")) {
                return Optional.of(false);
            }
            if (node.has("changed") && !bool(node, "changed")) {
                return Optional.of(false);
            }
            if (bool(node, "condition_satisfied", "conditionSatisfied")
                    && bool(node, "requires_ai_analysis", "requiresAiAnalysis")) {
                return Optional.of(true);
            }
            if (node.has("condition_satisfied") || node.has("conditionSatisfied")
                    || node.has("requires_ai_analysis") || node.has("requiresAiAnalysis")) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    private boolean hasSentNotification(
            SubscriptionContext subscription,
            List<SubscriptionContext> subscriptions,
            List<Execution> executions
    ) {
        return executions.stream()
                .filter(execution -> !execution.failed())
                .filter(execution -> isTool(execution, "send_notification"))
                .filter(execution -> subscriptions.size() == 1 || mentionsSubscription(execution, subscription))
                .map(Execution::output)
                .map(this::structuredNode)
                .flatMap(Optional::stream)
                .anyMatch(node -> bool(node, "sent"));
    }

    private boolean mentionsSubscription(Execution execution, SubscriptionContext subscription) {
        String subscriptionId = subscription.subscriptionId();
        return contains(execution.input(), subscriptionId) || contains(execution.output(), subscriptionId);
    }

    private boolean contains(String value, String expected) {
        return value != null && expected != null && value.contains(expected);
    }

    private boolean isTool(Execution execution, String toolName) {
        String name = execution.toolName();
        return name != null && (name.equals(toolName) || name.endsWith("_" + toolName));
    }

    private Optional<JsonNode> structuredNode(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (String candidate : jsonCandidates(content)) {
            Optional<JsonNode> root = readJson(candidate);
            if (root.isPresent()) {
                Optional<JsonNode> structured = findStructuredNode(root.get());
                if (structured.isPresent()) {
                    return structured;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findStructuredNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            JsonNode structured = node.get("structured");
            if (structured != null && structured.isObject()) {
                return Optional.of(structured);
            }
            JsonNode textNode = node.get("text");
            if (textNode != null && textNode.isTextual()) {
                Optional<JsonNode> nested = readJson(textNode.asText());
                if (nested.isPresent()) {
                    Optional<JsonNode> nestedStructured = findStructuredNode(nested.get());
                    if (nestedStructured.isPresent()) {
                        return nestedStructured;
                    }
                }
            }
            if (node.has("baseline_initialized") || node.has("condition_satisfied") || node.has("sent")) {
                return Optional.of(node);
            }
            return StreamSupport.stream(((Iterable<JsonNode>) node::elements).spliterator(), false)
                    .map(this::findStructuredNode)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                    .map(this::findStructuredNode)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        return Optional.empty();
    }

    private boolean isSuccessful(JsonNode node) {
        boolean notificationRequired = bool(node, "notificationRequired", "notification_required");
        return bool(node, "dataToolExecuted", "data_tool_executed")
                && bool(node, "compareExecuted", "compare_executed", "compareSubscriptionChangeExecuted")
                && (!notificationRequired || bool(node, "notificationSent", "notification_sent"));
    }

    private boolean bool(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asBoolean(false);
            }
        }
        return false;
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private record ExecutionResult(String content, List<Execution> executions) {
    }
}
