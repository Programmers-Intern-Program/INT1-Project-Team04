package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.back.domain.application.service.SubscriptionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

@DisplayName("Adapter: Spring AI 구독 모니터링 실행")
class SpringAiMonitorAdapterTest {

    @Test
    @DisplayName("ChatClient가 없으면 일반 모니터링 호출을 스킵한다")
    void skipsMonitorCallWhenChatClientIsNull() {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(null, new ObjectMapper());

        adapter.run();
        // null chatClient → 스킵, 예외 없음
    }

    @Test
    @DisplayName("ChatClient가 없으면 구독 실행을 실패로 처리한다")
    void failsExecuteWhenChatClientIsNull() {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(null, new ObjectMapper());

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("구독 목록이 비어 있으면 ChatClient를 호출하지 않는다")
    void skipsMonitorCallWhenSubscriptionListIsEmpty() {
        ChatClient chatClient = mock(ChatClient.class);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

        adapter.execute(List.of());

        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("모델 응답이 실행 결과 JSON이 아니면 실패로 처리한다")
    void failsWhenModelResponseDoesNotProveExecution() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("처리했습니다.");
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

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
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

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
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

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
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

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
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
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
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

        adapter.execute(List.of(subscription()));

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("알림이 필요한 실행에서 발송 증빙이 없으면 실패로 처리한다")
    void failsWhenNotificationWasRequiredButNotSent() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        {"results":[{"subscriptionId":"sub-1","dataToolExecuted":true,"compareExecuted":true,"notificationRequired":true,"notificationSent":false,"status":"FAILED"}]}
                        """);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper());

        assertThatThrownBy(() -> adapter.execute(List.of(subscription())))
                .isInstanceOf(RuntimeException.class);
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
}
