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

    @Test
    @DisplayName("MCP 도구 예외는 Vertex 함수 응답으로 안전한 JSON 에러를 반환한다")
    void returnsJsonErrorPayloadWhenToolCallbackThrows() throws Exception {
        ToolCallbackProvider provider = ToolCallbackProvider.from(new ThrowingToolCallback("""
                {"type":"object","properties":{}}
                """));

        ToolCallback adaptedCallback = GeminiToolSchemaAdapter.adapt(provider, objectMapper).getToolCallbacks()[0];
        String output = adaptedCallback.call("{}");
        JsonNode payload = objectMapper.readTree(output);

        assertThat(payload.path("error").asText()).isEqualTo("tool_call_failed");
        assertThat(payload.path("tool_name").asText()).isEqualTo("check_api_cache");
        assertThat(payload.path("message").asText()).contains("TimeoutException");
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

    private record ThrowingToolCallback(String schema) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("check_api_cache")
                    .description("캐시 확인")
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public String call(String input) {
            throw new RuntimeException("java.util.concurrent.TimeoutException: Did not observe any item");
        }
    }
}
