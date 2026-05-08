package com.back.domain.adapter.out.notification;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationSendResult;
import com.back.global.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpNotificationDeliveryAdapter implements SendNotificationDeliveryPort {

    private static final McpTool SEND_NOTIFICATION_TOOL = new McpTool(
            null,
            null,
            null,
            "send_notification",
            "MCP notification delivery tool",
            "{}"
    );

    private final ExecuteMcpToolPort executeMcpToolPort;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL
                || channel == NotificationChannel.DISCORD_DM
                || channel == NotificationChannel.TELEGRAM_DM;
    }

    @Override
    public NotificationSendResult send(NotificationDelivery delivery) {
        try {
            McpExecutionResult result = executeMcpToolPort.execute(
                    SEND_NOTIFICATION_TOOL,
                    Map.of("input", input(delivery))
            );
            return sendResult(result);
        } catch (ApiException exception) {
            return NotificationSendResult.retryableFailure("MCP notification request failed");
        }
    }

    private Map<String, Object> input(NotificationDelivery delivery) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("notificationChannel", delivery.channel().name());
        input.put("notificationTarget", delivery.recipient());
        input.put("title", delivery.title());
        input.put("message", delivery.message());
        input.put("subscriptionId", delivery.subscriptionId());
        input.put("idempotency_key", delivery.alertEventId() + ":" + delivery.id());
        input.put("metadata", Map.of(
                "deliveryId", delivery.id(),
                "alertEventId", delivery.alertEventId(),
                "userId", delivery.userId()
        ));
        return input;
    }

    private NotificationSendResult sendResult(McpExecutionResult result) {
        JsonNode structured = structured(result.metadata());
        if (structured.path("sent").asBoolean(false)) {
            return NotificationSendResult.success(text(structured, "provider_message_id"));
        }

        String reason = text(structured, "error");
        if (isBlank(reason)) {
            reason = "MCP notification tool returned sent=false";
        }
        if (structured.path("retryable").asBoolean(false)) {
            return NotificationSendResult.retryableFailure(reason);
        }
        return NotificationSendResult.permanentFailure(reason);
    }

    private JsonNode structured(String metadata) {
        try {
            JsonNode root = objectMapper.readTree(metadata == null ? "" : metadata);
            JsonNode structured = root.path("structured");
            return structured.isObject() ? structured : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
