package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.back.domain.application.service.SubscriptionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

@DisplayName("Adapter: Spring AI 구독 모니터링 실행")
class SpringAiMonitorAdapterTest {

    @Test
    @DisplayName("ChatClient가 없으면 일반 모니터링 호출을 스킵한다")
    void skipsMonitorCallWhenChatClientIsNull() {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(null, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.run();
        // null chatClient → 스킵, 예외 없음
    }

    @Test
    @DisplayName("ChatClient가 없으면 구독 실행을 실패로 처리한다")
    void failsExecuteWhenChatClientIsNull() {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(null, new ObjectMapper(), Integer.MAX_VALUE);

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("구독 목록이 비어 있으면 ChatClient를 호출하지 않는다")
    void skipsMonitorCallWhenSubscriptionListIsEmpty() {
        ChatClient chatClient = mock(ChatClient.class);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of());

        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("모델 응답이 실행 결과 JSON이 아니면 실패로 처리한다")
    void failsWhenModelResponseDoesNotProveExecution() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("처리했습니다.");
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("데이터 도구와 비교 도구 실행이 확인되면 성공으로 처리한다")
    void succeedsWhenDataAndCompareToolExecutionIsProven() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        {"results":[{"subscriptionId":"sub-1","dataToolExecuted":true,"compareExecuted":true,"notificationRequired":false,"notificationSent":false,"status":"NO_CHANGE"}]}
                        """);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));
    }

    @Test
    @DisplayName("모델이 코드블록으로 감싼 실행 증빙 JSON도 성공으로 처리한다")
    void succeedsWhenExecutionEvidenceJsonIsWrappedInCodeBlock() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        처리 결과입니다.
                        ```json
                        {"results":[{"subscriptionId":"sub-1","dataToolExecuted":true,"compareExecuted":true,"notificationRequired":false,"notificationSent":false,"status":"BASELINE_INITIALIZED"}]}
                        ```
                        """);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));
    }

    @Test
    @DisplayName("모델 최종 응답이 비어 있어도 MCP 도구 실행 기록이 있으면 성공으로 처리한다")
    void succeedsWhenToolExecutionEvidenceExistsWithoutFinalModelContent() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    McpToolExecutionRecorder.record("search_house_price", "{}", """
                            {"structured":{"summary":{"avg_deal_amount":106000}}}
                            """, false);
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));
    }

    @Test
    @DisplayName("MCP 비교 결과 알림이 필요하지만 발송 기록이 없으면 실패로 처리한다")
    void failsWhenToolEvidenceRequiresNotificationButNotificationWasNotSent() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    McpToolExecutionRecorder.record("search_house_price", "{}", """
                            {"structured":{"summary":{"avg_deal_amount":106000}}}
                            """, false);
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":false,"changed":true,"condition_satisfied":true,"requires_ai_analysis":true,"subscriptionId":"sub-1"}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("구독 변화 알림은 channel-v1 렌더링 증빙이 있어야 성공으로 인정한다")
    void failsWhenNotificationWasSentWithoutBriefingRenderedEvidence() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    McpToolExecutionRecorder.record("search_house_price", "{}", """
                            {"structured":{"summary":{"avg_deal_amount":106000}}}
                            """, false);
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":false,"changed":true,"condition_satisfied":true,"requires_ai_analysis":true,"subscriptionId":"sub-1"}}
                            """, false);
                    McpToolExecutionRecorder.record("send_notification", """
                            {"subscriptionId":"sub-1","notificationChannel":"TELEGRAM_DM","notificationTarget":"123456789"}
                            """, """
                            {"structured":{"sent":true,"provider":"telegram","subscriptionId":"sub-1"},"metadata":{"briefing_contract_version":"channel-v1","briefing_rendered":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("비교 결과 알림이 필요한데 발송이 누락되면 발송 도구를 한 번 더 유도한다")
    void retriesNotificationWhenCompareRequiresNotificationButSendWasMissing() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("search_house_price", "{}", """
                                {"structured":{"summary":{"avg_deal_amount":106000}}}
                                """, false);
                        McpToolExecutionRecorder.record("compare_subscription_change", """
                                {"subscriptionId":"sub-1"}
                                """, """
                                {"structured":{"baseline_initialized":false,"changed":true,"condition_satisfied":true,"requires_ai_analysis":true,"subscriptionId":"sub-1","diffs":[{"field":"avg_deal_amount","baseline_value":100000,"current_value":106000}]}}
                                """, false);
                        return null;
                    }
                    McpToolExecutionRecorder.record("send_notification", """
                            {"subscriptionId":"sub-1","notificationChannel":"TELEGRAM_DM","notificationTarget":"123456789"}
                            """, """
                            {"structured":{"sent":true,"provider":"telegram","subscriptionId":"sub-1"},"metadata":{"briefing_contract_version":"channel-v1","briefing_rendered":true}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("알림 재요청이 channel-v1 렌더링 없이 실패하면 더 엄격한 알림 payload로 다시 유도한다")
    void retriesNotificationWithStrictPayloadWhenFirstNotificationRetryWasNotRendered() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        McpToolExecutionRecorder.record("search_house_price", "{}", """
                                {"structured":{"summary":{"avg_deal_amount":106000}}}
                                """, false);
                        McpToolExecutionRecorder.record("compare_subscription_change", """
                                {"subscriptionId":"sub-1"}
                                """, """
                                {"structured":{"baseline_initialized":false,"changed":true,"condition_satisfied":true,"requires_ai_analysis":true,"subscriptionId":"sub-1","diffs":[{"field":"avg_deal_amount","baseline_value":100000,"current_value":106000,"change_rate":6.0}],"briefing_facts":["평균 매매가가 10.0억에서 10.6억으로 증가했습니다."]}}
                                """, false);
                        return null;
                    }
                    if (call == 1) {
                        McpToolExecutionRecorder.record("send_notification", """
                                {"subscriptionId":"sub-1","notificationChannel":"TELEGRAM_DM","notificationTarget":"123456789","message":"조건 충족"}
                                """, """
                                {"structured":{"sent":false,"provider":"briefing_contract","subscriptionId":"sub-1"},"metadata":{"briefing_contract_version":null,"briefing_rendered":false}}
                                """, false);
                        return null;
                    }
                    McpToolExecutionRecorder.record("send_notification", """
                            {"subscriptionId":"sub-1","notificationChannel":"TELEGRAM_DM","notificationTarget":"123456789","metadata":{"briefingContractVersion":"channel-v1","briefing":{"domain":"real-estate"}}}
                            """, """
                            {"structured":{"sent":true,"provider":"telegram","subscriptionId":"sub-1"},"metadata":{"briefing_contract_version":"channel-v1","briefing_rendered":true}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("알림 재시도 프롬프트는 채용/부동산 channel-v1 metadata 생략을 허용하지 않는다")
    void notificationRetryPromptRequiresChannelV1MetadataForFormattedDomains() throws Exception {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(mock(ChatClient.class), new ObjectMapper(), Integer.MAX_VALUE);
        Method method = SpringAiMonitorAdapter.class.getDeclaredMethod(
                "notificationRetryPrompt",
                String.class,
                List.class
        );
        method.setAccessible(true);

        String prompt = (String) method.invoke(adapter, """
                [{"subscriptionId":"sub-1","domain":"채용","notificationChannel":"DISCORD_DM","notificationTarget":"987654321012345678"}]
                """, List.of(new McpToolExecutionRecorder.Execution(
                "compare_subscription_change",
                "{\"subscriptionId\":\"sub-1\"}",
                """
                        {"structured":{"baseline_initialized":false,"changed":true,"condition_satisfied":true,"requires_ai_analysis":true,"subscriptionId":"sub-1","briefing_postings_by_source":{"public_job":[{"title":"국토연구원 연구직 채용","url":"https://example.com/jobs/1"}]}}}
                        """,
                false
        )));

        assertThat(prompt)
                .contains("briefingContractVersion=\"channel-v1\"")
                .contains("\"input\"")
                .contains("metadata를 생략하지 마세요")
                .contains("무성의한 한두 줄")
                .contains("채용 리스트")
                .contains("확인할 점")
                .doesNotContain("metadata를 생략하고");
    }

    @Test
    @DisplayName("엄격 알림 재시도 프롬프트는 실제 MCP schema처럼 input 래퍼로 send_notification을 호출하게 한다")
    void strictNotificationRetryPromptUsesMcpInputWrapper() throws Exception {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(mock(ChatClient.class), new ObjectMapper(), Integer.MAX_VALUE);
        Method method = SpringAiMonitorAdapter.class.getDeclaredMethod(
                "strictNotificationRetryPrompt",
                String.class,
                List.class
        );
        method.setAccessible(true);

        String prompt = (String) method.invoke(adapter, """
                [{"subscriptionId":"sub-1","domain":"부동산","notificationChannel":"DISCORD_DM","notificationTarget":"987654321012345678"}]
                """, List.of(new McpToolExecutionRecorder.Execution(
                "compare_subscription_change",
                "{\"subscriptionId\":\"sub-1\"}",
                """
                        {"structured":{"baseline_initialized":false,"changed":true,"condition_satisfied":true,"requires_ai_analysis":true,"subscriptionId":"sub-1","diffs":[{"field":"avg_deal_amount","baseline_value":100000,"current_value":95000,"change_rate":-5.0}],"briefing_facts":["평균 매매가가 10억원에서 9억5000만원으로 하락했습니다."]}}
                        """,
                false
        )));

        assertThat(prompt)
                .contains("\"input\": {")
                .contains("\"notificationChannel\"")
                .contains("\"metadata\"")
                .contains("최상위에는 input 하나만 두세요")
                .contains("부동산 알림")
                .contains("채용 알림")
                .contains("\"domain\": \"recruitment\"")
                .contains("채용 리스트")
                .contains("structured.briefing_postings_by_source")
                .contains("URL은 http:// 또는 https://로 시작");
    }

    @Test
    @DisplayName("데이터 도구만 실행된 첫 응답은 비교 도구 실행을 한 번 더 유도한다")
    void retriesOnceWhenDataToolWasExecutedWithoutCompare() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"cache_hit":false}
                                """, false);
                        McpToolExecutionRecorder.record("search_house_price", "{}", """
                                {"structured":{"summary":{"avg_deal_amount":106000}}}
                                """, false);
                        return null;
                    }
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("도구 응답 변환 예외가 나도 확보한 데이터 도구 증빙으로 비교 도구를 재유도한다")
    void retriesCompareWhenChatCallFailsAfterDataToolExecution() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"cache_hit":false}
                                """, false);
                        McpToolExecutionRecorder.record("search_house_price", "{}", """
                                {"structured":{"summary":{"avg_deal_amount":106000}}}
                                """, false);
                        throw new RuntimeException("Unrecognized token 'Error': was expecting JSON");
                    }
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("비교 도구가 구조화 결과 없이 오류 문자열만 반환하면 비교 도구를 재유도한다")
    void retriesCompareWhenCompareToolReturnedUnstructuredError() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"cache_hit":true}
                                """, false);
                        McpToolExecutionRecorder.record("search_house_price", "{}", """
                                {"structured":{"summary":{"avg_deal_amount":106000}}}
                                """, false);
                        McpToolExecutionRecorder.record("compare_subscription_change", """
                                {"subscriptionId":"sub-1","current":"Error executing tool"}
                                """, """
                                Error executing tool compare_subscription_change: current.structured 또는 current.structured.summary 가 필요합니다.
                                """, false);
                        return null;
                    }
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("데이터 도구 호출이 인자 문제로 실패하면 구독 params 기준으로 데이터 도구를 재유도한다")
    void retriesDataToolWhenFirstDataToolCallFailed() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"cache_hit":false}
                                """, false);
                        McpToolExecutionRecorder.record("search_house_price", """
                                {"region":"강남구"}
                                """, "Validation error: deal_ymd field required", true);
                        return null;
                    }
                    McpToolExecutionRecorder.record("search_house_price", """
                            {"region":"강남구","deal_ymd":"202403"}
                            """, """
                            {"structured":{"summary":{"avg_deal_amount":106000},"query":{"region":"강남구","deal_ymd":"202403"}}}
                            """, false);
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("캐시 확인만 하고 데이터 도구가 누락되면 구독 params 기준으로 데이터 도구를 재유도한다")
    void retriesDataToolWhenOnlyCacheCheckWasExecuted() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", """
                                {"input":{"tool_name":"search_public_job","params":{"keyword":"간호사"}}}
                                """, """
                                {"cache_hit":true,"tool_name":"search_public_job","params_hash":"hash-job"}
                                """, false);
                        return null;
                    }
                    McpToolExecutionRecorder.record("search_public_job", """
                            {"keyword":"간호사","recrut_pbanc_ttl":"간호사"}
                            """, """
                            {"structured":{"summary":{"count":1,"ongoing_count":0},"query":{"keyword":"간호사"}},"metadata":{"tool_name":"search_public_job"}}
                            """, false);
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("최종 JSON이 성공을 주장해도 비교 도구 실행 증빙이 없으면 비교를 다시 유도한다")
    void retriesCompareWhenFinalJsonClaimsSuccessButCompareToolWasMissing() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"cache_hit":false}
                                """, false);
                        McpToolExecutionRecorder.record("search_offi_trade", "{}", """
                                {"structured":{"summary":{"avg_deal_amount":32100}}}
                                """, false);
                        return """
                                {"results":[{"subscriptionId":"sub-1","dataToolExecuted":true,"compareExecuted":true,"notificationRequired":false,"notificationSent":false,"status":"BASELINE_INITIALIZED"}]}
                                """;
                    }
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("비교 도구 재요청도 도구 호출 없이 끝나면 더 좁은 재요청으로 비교를 다시 유도한다")
    void retriesCompareWithNarrowPromptWhenFirstRetryStillMissesCompare() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"last_fetched_at":"2026-05-08T15:05:44+09:00"}
                                """, false);
                        McpToolExecutionRecorder.record("get_cached_data", """
                                {"tool_name":"search_public_job","params":{"keyword":"국토연구원"}}
                                """, """
                                {"structured":{"summary":{"count":1},"postings":[{"id":"public_job:1","title":"국토연구원 채용"}]}}
                                """, false);
                        return null;
                    }
                    if (call == 1) {
                        return "compare를 처리했습니다.";
                    }
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("좁은 비교 재요청도 누락되면 구조화된 compare input으로 한 번 더 유도한다")
    void retriesCompareWithStructuredInputWhenNarrowRetryStillMissesCompare() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        McpToolExecutionRecorder.record("check_api_cache", "{}", """
                                {"last_fetched_at":null}
                                """, false);
                        McpToolExecutionRecorder.record("search_rh_rent", "{}", """
                                {"text":"강남구 연립다세대 전월세","structured":{"summary":{"count":100,"avg_deposit":16727},"query":{"region":"강남구","deal_ymd":"202403"}},"metadata":{"tool_name":"search_rh_rent"}}
                                """, false);
                        return null;
                    }
                    if (call == 1 || call == 2) {
                        return "compare를 처리했습니다.";
                    }
                    McpToolExecutionRecorder.record("compare_subscription_change", """
                            {"subscriptionId":"sub-1"}
                            """, """
                            {"structured":{"baseline_initialized":true,"changed":false,"subscriptionId":"sub-1","requires_ai_analysis":false}}
                            """, false);
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(4);
    }

    @Test
    @DisplayName("알림이 필요한 실행에서 발송 증빙이 없으면 실패로 처리한다")
    void failsWhenNotificationWasRequiredButNotSent() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        {"results":[{"subscriptionId":"sub-1","dataToolExecuted":true,"compareExecuted":true,"notificationRequired":true,"notificationSent":false,"status":"FAILED"}]}
                        """);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("최종 JSON의 notificationSent=true만으로 구독 변화 알림 성공을 인정하지 않는다")
    void failsWhenFinalJsonClaimsNotificationSentWithoutRenderedToolEvidence() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        {"results":[{"subscriptionId":"sub-1","dataToolExecuted":true,"compareExecuted":true,"notificationRequired":true,"notificationSent":true,"status":"SENT"}]}
                        """);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("여러 구독이 병렬로 실행된다")
    void executesSubscriptionsConcurrently() throws InterruptedException {
        int count = 5;
        CountDownLatch allStarted = new CountDownLatch(count);
        CountDownLatch release = new CountDownLatch(1);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(inv -> {
                    allStarted.countDown();
                    release.await();
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), Integer.MAX_VALUE);

        Thread t = Thread.ofVirtual().start(() -> {
            try { adapter.execute(subs(count)); } catch (Exception ignored) {}
        });

        assertTrue(allStarted.await(3, TimeUnit.SECONDS), "5개 구독이 동시에 시작되지 않았다");
        release.countDown();
        t.join(3000);
    }

    @Test
    @DisplayName("Semaphore가 동시 실행 수를 제한한다")
    void semaphoreLimitsConcurrency() throws InterruptedException {
        int limit = 2;
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch release = new CountDownLatch(1);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(inv -> {
                    int current = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    release.await();
                    concurrent.decrementAndGet();
                    return null;
                });
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), limit);

        Thread t = Thread.ofVirtual().start(() -> {
            try { adapter.execute(subs(5)); } catch (Exception ignored) {}
        });

        Thread.sleep(200);
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(limit);
        release.countDown();
        t.join(3000);
    }

    private SubscriptionContext subscription() {
        return new SubscriptionContext(
                "sub-1",
                "real-estate",
                "강남구 아파트 매매",
                Map.of("region", "강남구", "deal_ymd", "202403"),
                "TELEGRAM_DM",
                "123456789"
        );
    }

    private static List<SubscriptionContext> subs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new SubscriptionContext(
                        "sub-" + i, "real-estate", "query", Map.of(), "DISCORD_DM", "target"))
                .toList();
    }
}
