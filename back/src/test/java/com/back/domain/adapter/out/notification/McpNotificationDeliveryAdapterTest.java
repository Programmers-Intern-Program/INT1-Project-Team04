package com.back.domain.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationSendResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: MCP 알림 발송 위임 테스트")
class McpNotificationDeliveryAdapterTest {

    @Test
    @DisplayName("Adapter: NotificationDelivery를 send_notification MCP 도구 입력으로 위임한다")
    void delegatesDeliveryToMcpNotificationTool() {
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort("""
                {
                  "structured": {
                    "sent": true,
                    "provider_message_id": "provider-123",
                    "retryable": false
                  }
                }
                """);
        McpNotificationDeliveryAdapter adapter = new McpNotificationDeliveryAdapter(
                executeMcpToolPort,
                new ObjectMapper()
        );

        NotificationSendResult result = adapter.send(delivery(NotificationChannel.TELEGRAM_DM, "123456789"));

        assertThat(adapter.supports(NotificationChannel.EMAIL)).isTrue();
        assertThat(adapter.supports(NotificationChannel.DISCORD_DM)).isTrue();
        assertThat(adapter.supports(NotificationChannel.TELEGRAM_DM)).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("provider-123");
        assertThat(executeMcpToolPort.tool.name()).isEqualTo("send_notification");
        assertThat(executeMcpToolPort.arguments).containsOnlyKeys("input");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) executeMcpToolPort.arguments.get("input");
        assertThat(input)
                .containsEntry("notificationChannel", "TELEGRAM_DM")
                .containsEntry("notificationTarget", "123456789")
                .containsEntry("subscriptionId", "sub-1")
                .containsEntry("title", "강남구 전세 매물 변화")
                .containsEntry("message", "조건에 맞는 신규 매물이 2건 발견되었습니다.");
    }

    @Test
    @DisplayName("Adapter: MCP 도구가 재시도 가능 실패를 반환하면 retryable failure로 매핑한다")
    void mapsRetryableMcpFailure() {
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort("""
                {
                  "structured": {
                    "sent": false,
                    "retryable": true,
                    "error": "telegram rate limited"
                  }
                }
                """);
        McpNotificationDeliveryAdapter adapter = new McpNotificationDeliveryAdapter(
                executeMcpToolPort,
                new ObjectMapper()
        );

        NotificationSendResult result = adapter.send(delivery(NotificationChannel.TELEGRAM_DM, "123456789"));

        assertThat(result.successful()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.failureReason()).isEqualTo("telegram rate limited");
    }

    private NotificationDelivery delivery(NotificationChannel channel, String recipient) {
        return new NotificationDelivery(
                "delivery-1",
                "alert-1",
                "sub-1",
                1L,
                channel,
                recipient,
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                NotificationDeliveryStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        );
    }

    private static class FakeExecuteMcpToolPort implements ExecuteMcpToolPort {
        private final String metadata;
        private McpTool tool;
        private Map<String, Object> arguments;

        private FakeExecuteMcpToolPort(String metadata) {
            this.metadata = metadata;
        }

        @Override
        public McpExecutionResult execute(McpTool mcpTool, Map<String, Object> arguments) {
            this.tool = mcpTool;
            this.arguments = arguments;
            return new McpExecutionResult("NOTIFICATION", "notification result", metadata);
        }
    }
}
