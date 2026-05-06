package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.RunAiMonitorPort;
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.application.service.SubscriptionContext;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpringAiMonitorAdapter implements RunAiMonitorPort, RunSubscriptionExecutionPort {

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
            String content = monitorChatClient.prompt()
                    .system(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                    .user(payload)
                    .call()
                    .content();
            verifyExecutionResponse(content, subscriptions);
        } catch (JsonProcessingException e) {
            log.error("[SpringAiMonitorAdapter] 구독 context 직렬화 실패", e);
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    private void verifyExecutionResponse(String content, List<SubscriptionContext> subscriptions) {
        JsonNode resultsNode = readResultsNode(content);
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

    private JsonNode readResultsNode(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode resultsNode = root.isArray() ? root : root.path("results");
            if (!resultsNode.isArray()) {
                throw new ApiException(ErrorCode.AI_PARSE_FAILED);
            }
            return resultsNode;
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
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
}
