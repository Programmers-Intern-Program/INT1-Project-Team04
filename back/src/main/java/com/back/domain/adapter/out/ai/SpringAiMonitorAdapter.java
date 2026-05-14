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
import java.util.concurrent.Semaphore;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpringAiMonitorAdapter implements RunAiMonitorPort, RunSubscriptionExecutionPort {

    private static final int  RATE_LIMIT_MAX_RETRY    = 3;
    private static final long RATE_LIMIT_BASE_DELAY_MS = 2000; // 2s → 4s → 8s

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
    // MCP 알림 renderer가 성공 증빙으로 돌려주는 표준 브리핑 계약 버전.
    private static final String BRIEFING_CONTRACT_VERSION = "channel-v1";

    @Nullable
    private final ChatClient monitorChatClient;
    private final ObjectMapper objectMapper;
    // bean 필드로 선언해 모든 서비스 VT가 동일한 permit pool을 공유한다.
    // 호출 단위로 new Semaphore()를 만들면 VT마다 별도 제한이 생겨 전역 제어가 풀린다.
    private final Semaphore semaphore;

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
            ObjectMapper objectMapper,
            @Value("${app.monitor.concurrency-limit:5}") int concurrencyLimit
    ) {
        this.monitorChatClient = monitorChatClient;
        this.objectMapper = objectMapper;
        this.semaphore = new Semaphore(concurrencyLimit);
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

    // 서비스 레이어 VT에서 1건씩 호출된다.
    // Semaphore: proactive guard — 동시 세션 수를 제한해 429 발생 자체를 줄인다.
    // requestMonitorExecution() retry: reactive fallback — 429가 나면 backoff 후 재시도.
    // 둘은 역할이 다르다. retry만으로는 동시 구독이 많을 때 thundering herd를 막지 못한다.
    @Override
    public void execute(SubscriptionContext subscription) {
        if (monitorChatClient == null) {
            log.warn("[SpringAiMonitorAdapter] ChatClient 미구성 — 구독 실행 스킵. subscriptionId={}", subscription.subscriptionId());
            return;
        }
        // acquireUninterruptibly: 서비스 VT의 Runnable 람다는 checked exception 불가
        semaphore.acquireUninterruptibly();
        try {
            processSubscription(subscription);
        } finally {
            semaphore.release();
        }
    }

    // AI 실행 로직만 담당. 동시성 관련 코드 없음 — Semaphore는 execute()가 처리.
    // package-private: 테스트에서 동기 직접 호출 가능
    void processSubscription(SubscriptionContext subscription) {
        log.info("[SpringAiMonitorAdapter] 구독 {} 실행", subscription.subscriptionId());
        try {
            List<SubscriptionContext> asList = List.of(subscription);
            String payload = objectMapper.writeValueAsString(asList);
            ExecutionResult result = requestMonitorExecution(payload);
            String content = result.content();
            List<Execution> executions = result.executions();
            if (shouldRetryMissingDataAfterCache(executions)) {
                // 모델이 캐시 확인 결과만 보고 멈춘 경우, 구독 params의 dataToolName으로 실제 데이터 도구 호출을 재유도한다.
                ExecutionResult dataRetryResult = requestMonitorExecution(dataToolAfterCacheRetryPrompt(payload, executions));
                content = dataRetryResult.content();
                executions = mergeExecutions(executions, dataRetryResult.executions());
            }
            if (shouldRetryFailedDataTool(executions)) {
                // 모델이 데이터 도구 인자를 잘못 구성해 실패한 경우, 구독 params의 실행 계약으로 한 번 더 좁힌다.
                ExecutionResult dataRetryResult = requestMonitorExecution(dataToolRetryPrompt(payload, executions));
                content = dataRetryResult.content();
                executions = mergeExecutions(executions, dataRetryResult.executions());
            }
            if (shouldRetryMissingCompare(content, asList, executions)) {
                // 데이터 조회까지 성공한 뒤 멈춘 경우, 백엔드 계산 대신 MCP compare 호출만 한 번 더 유도한다.
                ExecutionResult retryResult = requestMonitorExecution(retryPrompt(payload, executions));
                executions = mergeExecutions(executions, retryResult.executions());
                if (hasMissingCompare(asList, executions)) {
                    // 일반 재요청도 자연어 응답으로 끝나면 compare 도구 하나만 더 좁게 강제한다.
                    ExecutionResult narrowRetryResult = requestMonitorExecution(narrowCompareRetryPrompt(payload, executions));
                    executions = mergeExecutions(executions, narrowRetryResult.executions());
                }
                if (hasMissingCompare(asList, executions)) {
                    // 마지막으로 compare input 후보를 구조화해서 넘겨 모델의 임의 요약 응답을 줄인다.
                    ExecutionResult strictRetryResult = requestMonitorExecution(
                            strictCompareRetryPrompt(asList, executions)
                    );
                    executions = mergeExecutions(executions, strictRetryResult.executions());
                }
                if (shouldRetryMissingNotification(asList, executions)) {
                    // compare가 알림 필요로 확정한 경우에는 누락된 send_notification만 재유도한다.
                    ExecutionResult notificationRetryResult = requestMonitorExecution(notificationRetryPrompt(payload, executions));
                    executions = mergeExecutions(executions, notificationRetryResult.executions());
                }
                if (shouldRetryMissingNotification(asList, executions)) {
                    // 일반 알림 재시도도 실패하면 MCP input 래퍼까지 명시한 엄격 프롬프트로 마지막 보강을 시도한다.
                    ExecutionResult strictNotificationRetryResult = requestMonitorExecution(
                            strictNotificationRetryPrompt(payload, executions)
                    );
                    executions = mergeExecutions(executions, strictNotificationRetryResult.executions());
                }
                verifyToolExecutionEvidence(asList, executions);
                return;
            }
            if (shouldRetryMissingNotification(asList, executions)) {
                // 데이터/비교는 끝났지만 발송 증빙만 빠진 케이스는 알림 도구 호출만 보강한다.
                ExecutionResult notificationRetryResult = requestMonitorExecution(notificationRetryPrompt(payload, executions));
                executions = mergeExecutions(executions, notificationRetryResult.executions());
                if (shouldRetryMissingNotification(asList, executions)) {
                    // 발송 도구 호출 형식 자체가 흔들린 경우를 대비해 실제 schema 예시를 포함해 재요청한다.
                    ExecutionResult strictNotificationRetryResult = requestMonitorExecution(
                            strictNotificationRetryPrompt(payload, executions)
                    );
                    executions = mergeExecutions(executions, strictNotificationRetryResult.executions());
                }
                verifyToolExecutionEvidence(asList, executions);
                return;
            }
            verifyExecutionResponse(content, asList, executions);
        } catch (JsonProcessingException e) {
            log.error("[SpringAiMonitorAdapter] 구독 {} 직렬화 실패", subscription.subscriptionId(), e);
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }
    }

    // Semaphore를 유지하면서도 rate limit이 발생하면 해당 호출만 재시도한다.
    // 이전 tool 실행 결과는 보존되므로 retry가 처음부터 다시 시작하지 않는다.
    // Gemini가 도구 호출 후 최종 text를 비우는 경우가 있어 MCP 콜백 실행 기록도 함께 본다.
    private ExecutionResult requestMonitorExecution(String userPrompt) {
        for (int attempt = 0; ; attempt++) {
            McpToolExecutionRecorder.start();
            String content = null;
            List<Execution> executions;
            RuntimeException failure = null;
            try {
                content = monitorChatClient.prompt()
                        .system(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content();
            } catch (RuntimeException exception) {
                failure = exception;
            } finally {
                executions = McpToolExecutionRecorder.stop();
            }
            if (failure == null) {
                return new ExecutionResult(content, executions);
            }
            if (!executions.isEmpty()) {
                log.warn(
                        "[SpringAiMonitorAdapter] 모델 호출이 도구 실행 후 실패했습니다. 확보한 도구 증빙으로 후속 검증을 진행합니다. tools={}",
                        toolNames(executions),
                        failure
                );
                return new ExecutionResult(content, executions);
            }
            if (isRateLimitException(failure) && attempt < RATE_LIMIT_MAX_RETRY) {
                long delay = RATE_LIMIT_BASE_DELAY_MS << attempt;
                log.warn("[SpringAiMonitorAdapter] rate limit — {}ms 후 재시도 ({}/{})",
                        delay, attempt + 1, RATE_LIMIT_MAX_RETRY);
                try {
                    Thread.sleep(delay); // VT: carrier thread 반납하고 대기
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED);
                }
                continue;
            }
            if (isRateLimitException(failure)) {
                throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED);
            }
            if (isTimeoutException(failure)) {
                log.warn("[SpringAiMonitorAdapter] MCP 응답 타임아웃 — 구독 스킵. subscriptionId 확인은 호출부 로그 참조");
            }
            throw failure;
        }
    }

    // 예외 체인 전체를 탐색 — 원인이 깊이 래핑될 수 있음
    // 실제 Vertex AI 429 예외 타입은 첫 발생 시 로그로 확인 후 보정 필요
    private boolean isTimeoutException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) return true;
            String msg = t.getMessage() == null ? "" : t.getMessage();
            if (msg.contains("TimeoutException") || msg.contains("Did not observe any item")) return true;
        }
        return false;
    }

    private boolean isRateLimitException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            String msg  = t.getMessage() == null ? "" : t.getMessage();
            if (name.contains("ResourceExhausted") || name.contains("TooManyRequests")) return true;
            if (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED"))              return true;
        }
        return false;
    }

    private boolean shouldRetryFailedDataTool(List<Execution> executions) {
        // 데이터 도구가 실패했으면 같은 턴에서 compare로 넘어가지 말고 데이터 조회부터 복구한다.
        return !hasSuccessfulDataToolExecution(executions)
                && executions.stream()
                        .anyMatch(execution -> execution.failed() && isDataToolExecution(execution));
    }

    private boolean shouldRetryMissingDataAfterCache(List<Execution> executions) {
        // 캐시 확인만 성공한 상태는 실제 current 데이터가 없으므로 dataToolName 기반 조회를 다시 유도한다.
        return !hasSuccessfulDataToolExecution(executions)
                && executions.stream()
                        .anyMatch(execution -> isTool(execution, "check_api_cache"));
    }

    private boolean shouldRetryMissingCompare(
            String content,
            List<SubscriptionContext> subscriptions,
            List<Execution> executions
    ) {
        // 최종 응답 JSON보다 실제 data tool 이후 compare tool 실행 여부를 우선 확인한다.
        if (!hasSuccessfulDataToolExecution(executions)) {
            return false;
        }
        return hasMissingCompare(subscriptions, executions);
    }

    private boolean hasMissingCompare(List<SubscriptionContext> subscriptions, List<Execution> executions) {
        // compare 재시도 분기와 최종 검증이 같은 누락 기준을 공유한다.
        if (!hasSuccessfulDataToolExecution(executions)) {
            return false;
        }
        return subscriptions.stream()
                .anyMatch(subscription -> compareExecutions(subscription, subscriptions, executions).stream()
                        .noneMatch(this::hasStructuredCompareDecision));
    }

    private boolean hasStructuredCompareDecision(Execution execution) {
        Optional<JsonNode> structured = structuredNode(execution.output());
        if (structured.isEmpty()) {
            return false;
        }
        JsonNode node = structured.get();
        return node.has("baseline_initialized")
                || node.has("baselineInitialized")
                || node.has("changed")
                || node.has("condition_satisfied")
                || node.has("conditionSatisfied")
                || node.has("requires_ai_analysis")
                || node.has("requiresAiAnalysis");
    }

    private boolean shouldRetryMissingNotification(
            List<SubscriptionContext> subscriptions,
            List<Execution> executions
    ) {
        // 알림 필요 여부는 모델 문장이 아니라 compare_subscription_change의 구조화 결과로 판단한다.
        return subscriptions.stream()
                .anyMatch(subscription -> {
                    List<Execution> compareExecutions = compareExecutions(subscription, subscriptions, executions);
                    boolean notificationRequired = notificationRequired(compareExecutions).orElse(false);
                    return notificationRequired && !hasSentNotification(subscription, subscriptions, executions);
                });
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

    private String narrowCompareRetryPrompt(String payload, List<Execution> executions) {
        // 첫 compare 재요청보다 더 좁게, 이미 확보한 data tool 응답만 보고 compare 호출을 강제한다.
        return """
                이전 재요청도 compare_subscription_change MCP tool 호출 없이 종료되었습니다.
                이번 턴의 목표는 자연어 설명이 아니라 누락된 MCP tool call 실행입니다.

                원래 구독 JSON:
                %s

                이미 확보한 데이터 도구 응답:
                %s

                반드시 지킬 규칙:
                - check_api_cache, get_cached_data, search_* 데이터 도구는 다시 호출하지 마세요.
                - 이미 확보한 데이터 도구 응답을 current로 사용하세요.
                - 누락된 구독마다 compare_subscription_change를 반드시 호출하세요.
                - compare 결과에서 structured.condition_satisfied=true 이고 structured.requires_ai_analysis=true 이면 send_notification도 호출하세요.
                - 최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(payload, dataExecutionEvidenceForRetry(executions));
    }

    private String strictCompareRetryPrompt(List<SubscriptionContext> subscriptions, List<Execution> executions) {
        // 모델이 compare 호출 대신 설명문을 반복할 때는 백엔드가 만든 compare input 후보를 그대로 넘긴다.
        return """
                이전 재요청들도 compare_subscription_change MCP tool 호출 없이 종료되었습니다.
                이번 턴에서는 아래 compareInputs 배열의 각 객체를 compare_subscription_change 도구의 input 인자로 그대로 넣어 호출하세요.
                자연어 설명을 하지 말고 MCP tool call을 실행하세요.

                compareInputs:
                %s

                반드시 지킬 규칙:
                - check_api_cache, get_cached_data, search_* 데이터 도구는 다시 호출하지 마세요.
                - compare_subscription_change만 누락된 구독마다 호출하세요.
                - compare 결과가 baseline_initialized=true이면 send_notification을 호출하지 마세요.
                - 최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(compareInputsForRetry(subscriptions, executions));
    }

    private String dataToolRetryPrompt(String payload, List<Execution> executions) {
        // 실패한 데이터 도구의 잘못된 인자를 보여주고, 구독 params 기반의 최소 인자만 다시 쓰게 한다.
        return """
                이전 구독 실행에서 데이터 도구 호출이 실패했습니다.
                이번 턴의 목표는 자연어 설명이 아니라 실패한 데이터 도구를 정확한 인자로 다시 호출하는 것입니다.

                원래 구독 JSON:
                %s

                실패한 MCP 도구 기록:
                %s

                반드시 지킬 규칙:
                - check_api_cache는 이미 호출했으므로 다시 호출하지 마세요.
                - 각 구독의 params.dataToolName에 적힌 데이터 도구만 다시 호출하세요.
                - 부동산 데이터 도구(search_house_price, search_apt_rent, search_offi_trade, search_offi_rent, search_rh_trade, search_rh_rent)는 MCP 인자 input 객체에 region=params.region, deal_ymd=params.deal_ymd만 넣어 호출하세요.
                - dealYmdPolicy, conditionMetric, conditionDirection, conditionOperator, conditionThreshold, conditionUnit, dataToolName은 부동산 데이터 도구 input에 넣지 마세요.
                - 데이터 도구가 성공하면 그 응답을 current로 사용해 compare_subscription_change를 호출하세요.
                - compare 결과가 baseline_initialized=true이면 send_notification을 호출하지 마세요.
                - 최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(payload, failedExecutionEvidenceForRetry(executions));
    }

    private String dataToolAfterCacheRetryPrompt(String payload, List<Execution> executions) {
        // cache hit 여부와 무관하게 compare에는 실제 data tool payload가 필요하므로 누락된 조회를 보강한다.
        return """
                이전 구독 실행에서 check_api_cache만 호출되고 실제 데이터 도구 호출이 누락되었습니다.
                check_api_cache가 실패했더라도 이번 턴에서는 캐시 확인을 반복하지 말고 데이터 도구 호출로 복구하세요.
                이번 턴의 목표는 자연어 설명이 아니라 누락된 데이터 도구 MCP tool call 실행입니다.

                원래 구독 JSON:
                %s

                이미 실행된 캐시 확인 기록:
                %s

                반드시 지킬 규칙:
                - check_api_cache는 이미 호출했으므로 다시 호출하지 마세요.
                - 각 구독의 params.dataToolName에 적힌 데이터 도구만 호출하세요.
                - 부동산 데이터 도구(search_house_price, search_apt_rent, search_offi_trade, search_offi_rent, search_rh_trade, search_rh_rent)는 MCP 인자 input 객체에 region=params.region, deal_ymd=params.deal_ymd만 넣어 호출하세요.
                - 채용 데이터 도구(search_public_job, search_worknet_job)는 params에 있는 keyword, recrut_pbanc_ttl 등 조회 필드만 input에 넣고 condition*, dataToolName은 넣지 마세요.
                - 데이터 도구가 성공하면 그 응답을 current로 사용해 compare_subscription_change를 호출하세요.
                - compare 결과가 baseline_initialized=true이면 send_notification을 호출하지 마세요.
                - compare 결과에서 structured.condition_satisfied=true 이고 structured.requires_ai_analysis=true 이면 channel-v1 briefing metadata를 포함해 send_notification을 호출하세요.
                - 최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(payload, executionEvidenceForRetry(executions));
    }

    private String notificationRetryPrompt(String payload, List<Execution> executions) {
        // compare 결과가 알림 필요로 확정한 뒤에는 fetch/compare 반복 없이 발송 도구만 호출하게 한다.
        return """
                compare_subscription_change 결과상 알림이 필요하지만 send_notification MCP tool 호출 증빙이 없습니다.
                이번 턴의 목표는 누락된 알림 발송 tool call 실행입니다.

                원래 구독 JSON:
                %s

                compare_subscription_change 응답:
                %s

                반드시 지킬 규칙:
                - check_api_cache, get_cached_data, search_* 데이터 도구는 다시 호출하지 마세요.
                - compare_subscription_change도 다시 호출하지 마세요.
                - structured.condition_satisfied=true 이고 structured.requires_ai_analysis=true 인 구독에 대해서만 send_notification을 호출하세요.
                - send_notification MCP tool의 실제 schema는 최상위 {"input": {...}} 래퍼입니다. 최상위에는 input 하나만 두세요.
                - input 안에는 원래 구독 JSON의 notificationChannel과 notificationTarget을 그대로 사용하세요.
                - title/message에는 structured.diffs, structured.briefing_facts, structured.briefing_postings_by_source를 근거로 한 간결한 한국어 브리핑을 넣으세요.
                - 부동산/채용 구독은 send_notification input.metadata에 briefingContractVersion="channel-v1"와 briefing 객체를 반드시 포함하고 metadata를 생략하지 마세요.
                - briefing 객체는 domain, title, summary, changes, watchInfo, sources, interpretation 필드를 사용하세요.
                - 무성의한 한두 줄 briefing은 허용되지 않습니다. title, summary, interpretation은 완성된 한국어 문장으로 작성하세요.
                - 채용은 sources에 실제 공고 제목과 URL을 넣어 채용 리스트 섹션에 표시되게 하고, interpretation에는 확인할 점을 적으세요.
                - 부동산은 changes에 기준값/현재값, 변화율, 거래건수, 데이터 출처를 분리해서 넣고, interpretation에는 확인할 점을 적으세요.
                - 채용 briefing.sources에는 structured.briefing_postings_by_source의 신규 공고 title과 url을 반드시 포함하세요.
                - Discord/Telegram/Email 최종 양식은 MCP가 channel-v1 metadata로 렌더링하므로 임의 Markdown 양식을 만들지 마세요.
                - 최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(payload, compareExecutionEvidenceForRetry(executions));
    }

    private String strictNotificationRetryPrompt(String payload, List<Execution> executions) {
        // channel-v1 계약 누락이 반복될 때는 send_notification의 input 래퍼와 briefing 예시까지 고정한다.
        return """
                이전 알림 재요청이 send_notification 호출을 누락했거나 channel-v1 렌더링 증빙 없이 실패했습니다.
                이번 턴에서는 조건을 충족한 구독에 대해서만 send_notification MCP tool call을 다시 실행하세요.

                원래 구독 JSON:
                %s

                compare_subscription_change 응답:
                %s

                반드시 지킬 send_notification tool call 형식:
                {
                  "input": {
                    "subscriptionId": "원래 구독 JSON의 subscriptionId",
                    "notificationChannel": "원래 구독 JSON의 notificationChannel",
                    "notificationTarget": "원래 구독 JSON의 notificationTarget",
                    "title": "사용자에게 보일 짧은 한국어 제목",
                    "message": "channel-v1 렌더러가 대체하므로 간단한 요약만 작성",
                    "metadata": {
                      "briefingContractVersion": "channel-v1",
                      "briefing": {
                        "domain": "real-estate",
                        "title": "사용자에게 보일 제목",
                        "summary": "무엇이 얼마나 변했는지 한 문장 요약",
                        "changes": [
                          {"label": "평균 가격 또는 평균 보증금", "value": "기준값 → 현재값 (변화율)", "previous": "기준값", "current": "현재값"},
                          {"label": "변화율", "value": "증감률"},
                          {"label": "거래건수", "value": "현재 거래건수"},
                          {"label": "데이터 출처", "value": "국토교통부 실거래가"}
                        ],
                        "watchInfo": {
                          "target": "감시 대상",
                          "condition": "구독 조건",
                          "region": "지역",
                          "dealPeriod": "거래연월",
                          "dataSource": "국토교통부 실거래가"
                        },
                        "sources": [
                          {"label": "국토교통부 실거래가", "description": "공공 실거래 데이터"}
                        ],
                        "interpretation": "확인할 점으로 표시될 완성된 한국어 문장"
                      }
                    }
                  }
	                }

	                채용 알림 전용 briefing 예시(원래 구독 domain이 채용/jobs/recruitment이면 위 real-estate 예시 대신 이 형태를 사용):
	                {
	                  "input": {
	                    "subscriptionId": "원래 구독 JSON의 subscriptionId",
	                    "notificationChannel": "원래 구독 JSON의 notificationChannel",
	                    "notificationTarget": "원래 구독 JSON의 notificationTarget",
	                    "title": "공공기관 간호사 채용 새 공고",
	                    "message": "새로운 간호사 채용 공고가 올라왔습니다.",
	                    "metadata": {
	                      "briefingContractVersion": "channel-v1",
	                      "briefing": {
	                        "domain": "recruitment",
	                        "title": "공공기관 간호사 채용 새 공고",
	                        "summary": "새로운 간호사 채용 공고가 1건 올라왔습니다.",
	                        "changes": [
	                          {"label": "신규 공고", "value": "1건"},
	                          {"label": "검색 키워드", "value": "간호사"},
	                          {"label": "데이터 출처", "value": "공공기관 채용 공고"}
	                        ],
	                        "watchInfo": {
	                          "target": "공공기관 간호사 채용",
	                          "condition": "새 공고 1건 이상",
	                          "keyword": "간호사",
	                          "dataSource": "공공기관 채용 공고"
	                        },
	                        "sources": [
	                          {"label": "structured.briefing_postings_by_source의 실제 공고 title", "url": "https://example.com/job/1"}
	                        ],
	                        "interpretation": "공고 상세와 마감일을 확인한 뒤 지원 여부를 판단하세요."
	                      }
	                    }
	                  }
	                }

	                엄격한 규칙:
	                - 최상위에는 input 하나만 두세요. subscriptionId, notificationChannel, notificationTarget, metadata를 최상위에 직접 두지 마세요.
	                - metadata 안의 키는 briefingContractVersion처럼 camelCase로 작성하세요.
	                - 무성의한 한두 줄 briefing은 provider 전에 거부됩니다. title, summary, interpretation은 완성된 문장으로 쓰고 changes는 충분히 채우세요.
	                - 부동산 알림은 briefing.domain="real-estate"로 쓰고 changes에 기준값/현재값, 변화율, 거래건수, 데이터 출처를 포함하세요.
	                - 채용 알림은 briefing.domain="recruitment"로 쓰고 sources에 structured.briefing_postings_by_source의 실제 공고 제목과 URL을 그대로 복사해 채용 리스트 섹션에 표시되게 하세요.
	                - 채용 sources URL은 http:// 또는 https://로 시작해야 합니다. www.로 시작하는 값만 있으면 https://를 붙이세요.
	                - 채용 알림에는 real-estate 예시의 region, dealPeriod를 넣지 마세요.
	                - 부동산 알림에서는 briefing.watchInfo.region과 briefing.watchInfo.dealPeriod를 반드시 채우세요.
                - interpretation은 Discord/Telegram/Email에서 확인할 점 섹션으로 렌더링됩니다.
                - "cache", "API 캐시", raw JSON, serviceKey 같은 내부어는 title/message/briefing 어디에도 쓰지 마세요.
                - send_notification 결과의 metadata.briefing_rendered=true가 나오도록 해야 합니다.
                - 최종 응답은 기존 JSON 계약만 반환하세요.
                """.formatted(payload, compareExecutionEvidenceForRetry(executions));
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

    private String failedExecutionEvidenceForRetry(List<Execution> executions) {
        // 데이터 도구 재시도에는 실패한 tool/input/error만 남겨 프롬프트 노이즈를 줄인다.
        List<Map<String, Object>> evidence = executions.stream()
                .filter(Execution::failed)
                .map(execution -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("toolName", execution.toolName());
                    item.put("input", execution.input());
                    item.put("error", execution.output());
                    return item;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            return evidence.toString();
        }
    }

    private String compareInputsForRetry(List<SubscriptionContext> subscriptions, List<Execution> executions) {
        // compare가 계속 누락될 때 모델이 그대로 호출할 수 있는 최소 input 배열을 백엔드에서 만든다.
        List<Execution> dataExecutions = executions.stream()
                .filter(execution -> !execution.failed())
                .filter(this::isDataToolExecution)
                .toList();
        List<Map<String, Object>> compareInputs = new ArrayList<>();
        for (SubscriptionContext subscription : subscriptions) {
            Optional<Execution> dataExecution = dataExecutionForSubscription(subscription, subscriptions, dataExecutions);
            if (dataExecution.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("subscriptionId", subscription.subscriptionId());
            item.put("domain", subscription.domain());
            item.put("query", subscription.query());
            item.put("params", subscription.params());
            item.put("current", compactCurrentForCompare(dataExecution.get()));
            compareInputs.add(item);
        }
        try {
            return objectMapper.writeValueAsString(compareInputs);
        } catch (JsonProcessingException e) {
            return compareInputs.toString();
        }
    }

    private Optional<Execution> dataExecutionForSubscription(
            SubscriptionContext subscription,
            List<SubscriptionContext> subscriptions,
            List<Execution> dataExecutions
    ) {
        // 단일 구독 실행은 subscriptionId 언급이 없어도 확보한 데이터 응답 하나를 그대로 매칭한다.
        if (dataExecutions.isEmpty()) {
            return Optional.empty();
        }
        if (subscriptions.size() == 1 || dataExecutions.size() == 1) {
            return Optional.of(dataExecutions.getFirst());
        }
        return dataExecutions.stream()
                .filter(execution -> mentionsSubscription(execution, subscription))
                .findFirst()
                .or(() -> Optional.of(dataExecutions.getFirst()));
    }

    private Map<String, Object> compactCurrentForCompare(Execution dataExecution) {
        // compare 재시도 프롬프트에는 summary/query/source 정도만 남겨 토큰과 내부 노출을 줄인다.
        Map<String, Object> current = new HashMap<>();
        Optional<JsonNode> root = readFirstJson(dataExecution.output());
        if (root.isEmpty()) {
            current.put("text", dataExecution.output());
            return current;
        }
        JsonNode node = root.get();
        String currentText = text(node, "text");
        if (currentText != null && !currentText.isBlank()) {
            current.put("text", currentText);
        }
        JsonNode structured = node.get("structured");
        if (structured != null && structured.isObject()) {
            Map<String, Object> compactStructured = new HashMap<>();
            JsonNode summary = structured.get("summary");
            if (summary != null && !summary.isNull()) {
                compactStructured.put("summary", summary);
            }
            JsonNode query = structured.get("query");
            if (query != null && !query.isNull()) {
                compactStructured.put("query", query);
            }
            current.put("structured", compactStructured.isEmpty() ? structured : compactStructured);
        }
        JsonNode sourceUrl = node.get("source_url");
        if (sourceUrl != null && sourceUrl.isTextual()) {
            current.put("source_url", sourceUrl.asText());
        }
        JsonNode metadata = node.get("metadata");
        if (metadata != null && metadata.isObject()) {
            current.put("metadata", metadata);
        }
        return current;
    }

    private Optional<JsonNode> readFirstJson(String content) {
        // MCP tool output이 설명문이나 코드블록을 섞어도 첫 JSON 후보만 구조화해 재사용한다.
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (String candidate : jsonCandidates(content)) {
            Optional<JsonNode> root = readJson(candidate);
            if (root.isPresent()) {
                return root;
            }
        }
        return Optional.empty();
    }

    private String dataExecutionEvidenceForRetry(List<Execution> executions) {
        // 재요청 프롬프트에는 이미 확보한 데이터 응답만 전달해 fetch 중복 호출을 막는다.
        List<Map<String, Object>> evidence = executions.stream()
                .filter(execution -> !execution.failed())
                .filter(this::isDataToolExecution)
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

    private String compareExecutionEvidenceForRetry(List<Execution> executions) {
        // 알림 재시도는 compare 결과만 근거로 삼아 send_notification 호출 범위를 좁힌다.
        List<Map<String, Object>> evidence = executions.stream()
                .filter(execution -> !execution.failed())
                .filter(execution -> isTool(execution, "compare_subscription_change"))
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
        boolean notificationRequired = results.values().stream()
                .anyMatch(node -> bool(node, "notificationRequired", "notification_required"));
        if (notificationRequired) {
            // 최종 JSON의 notificationSent=true는 모델 주장일 뿐이라 실제 MCP 실행 기록으로 다시 검증한다.
            verifyToolExecutionEvidence(subscriptions, executions);
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
        return abbreviatedForLog(content, 500);
    }

    private String abbreviatedForLog(String content, int maxLength) {
        String value = content
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<email>")
                .replaceAll("(\"(?:target|notificationTarget)\"\\s*:\\s*\")[^\"]+", "$1<target>");
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
                log.warn(
                        "[SpringAiMonitorAdapter] 알림 발송 증빙 없음 - subscriptionId={}, tools={}, sendNotificationExecutions={}",
                        subscription.subscriptionId(),
                        toolNames(executions),
                        notificationExecutionsForLog(executions)
                );
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
        }
    }

    private List<Map<String, Object>> notificationExecutionsForLog(List<Execution> executions) {
        // 알림 실패 로그에는 수신자 식별값을 마스킹한 send_notification 결과만 남긴다.
        return executions.stream()
                .filter(execution -> isTool(execution, "send_notification"))
                .map(execution -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("failed", execution.failed());
                    item.put("input", abbreviatedForLog(execution.input(), 3000));
                    item.put("output", abbreviatedForLog(execution.output()));
                    return item;
                })
                .toList();
    }

    private List<String> toolNames(List<Execution> executions) {
        return executions.stream()
                .map(Execution::toolName)
                .toList();
    }

    private boolean hasSuccessfulDataToolExecution(List<Execution> executions) {
        return executions.stream()
                .anyMatch(execution -> !execution.failed() && isDataToolExecution(execution));
    }

    private boolean isDataToolExecution(Execution execution) {
        // check/get cache는 흐름 보조 도구이고, 실제 current 증빙은 도메인별 search 도구 실행이다.
        return DATA_TOOL_NAMES.stream().anyMatch(toolName -> isTool(execution, toolName));
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
                .anyMatch(execution -> isRenderedNotificationSent(execution.output()));
    }

    private boolean isRenderedNotificationSent(String output) {
        // 구독 변화 알림은 provider sent뿐 아니라 channel-v1 렌더링 완료 metadata까지 성공 증빙으로 본다.
        Optional<JsonNode> structured = structuredNode(output);
        if (structured.isEmpty() || !bool(structured.get(), "sent")) {
            return false;
        }
        Optional<JsonNode> metadata = metadataNode(output);
        if (metadata.isEmpty()) {
            return false;
        }
        JsonNode node = metadata.get();
        return BRIEFING_CONTRACT_VERSION.equals(text(node, "briefing_contract_version", "briefingContractVersion"))
                && bool(node, "briefing_rendered", "briefingRendered");
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

    private Optional<JsonNode> metadataNode(String content) {
        // MCP 응답이 text 안에 JSON으로 한 번 더 감싸지는 경우까지 metadata를 찾아낸다.
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (String candidate : jsonCandidates(content)) {
            Optional<JsonNode> root = readJson(candidate);
            if (root.isPresent()) {
                Optional<JsonNode> metadata = findMetadataNode(root.get());
                if (metadata.isPresent()) {
                    return metadata;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findMetadataNode(JsonNode node) {
        // MCP 공통 응답이 중첩되거나 text 안에 들어가도 첫 metadata 객체를 찾아낸다.
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            JsonNode metadata = node.get("metadata");
            if (metadata != null && metadata.isObject()) {
                return Optional.of(metadata);
            }
            JsonNode textNode = node.get("text");
            if (textNode != null && textNode.isTextual()) {
                Optional<JsonNode> nested = readJson(textNode.asText());
                if (nested.isPresent()) {
                    Optional<JsonNode> nestedMetadata = findMetadataNode(nested.get());
                    if (nestedMetadata.isPresent()) {
                        return nestedMetadata;
                    }
                }
            }
            return StreamSupport.stream(((Iterable<JsonNode>) node::elements).spliterator(), false)
                    .map(this::findMetadataNode)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                    .map(this::findMetadataNode)
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
