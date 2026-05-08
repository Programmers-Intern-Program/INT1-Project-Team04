package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

class GeminiToolSchemaAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("MCP 도구 스키마의 $defs 참조를 Gemini 호환 형태로 펼친다")
    void inlinesDefsReferenceForGeminiToolSchema() throws Exception {
        ToolCallbackProvider provider = ToolCallbackProvider.from(new StubToolCallback("""
                {
                  "$defs": {
                    "NotificationChannel": {
                      "type": "string",
                      "enum": ["TELEGRAM_DM", "DISCORD_DM", "EMAIL"]
                    }
                  },
                  "type": "object",
                  "properties": {
                    "notificationChannel": {
                      "$ref": "#/$defs/NotificationChannel",
                      "description": "알림 채널"
                    }
                  },
                  "required": ["notificationChannel"]
                }
                """));

        ToolCallback adaptedCallback = GeminiToolSchemaAdapter.adapt(provider, objectMapper).getToolCallbacks()[0];
        JsonNode schema = objectMapper.readTree(adaptedCallback.getToolDefinition().inputSchema());

        assertThat(schema.has("$defs")).isFalse();
        assertThat(schema.path("properties").path("notificationChannel").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("notificationChannel").path("description").asText()).isEqualTo("알림 채널");
        assertThat(schema.path("properties").path("notificationChannel").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("TELEGRAM_DM", "DISCORD_DM", "EMAIL");
    }

    private record StubToolCallback(String schema) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("send_notification")
                    .description("알림 발송")
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public String call(String input) {
            return "ok";
        }
    }
}
