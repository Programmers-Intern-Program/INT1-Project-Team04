package com.back.domain.adapter.out.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

// Spring AI MCP client가 받은 도구 스키마/실행은 그대로 쓰되,
// Gemini가 처리하지 못하는 JSON Schema 표현만 이 경계에서 보정한다.
final class GeminiToolSchemaAdapter {

    private GeminiToolSchemaAdapter() {
    }

    static ToolCallbackProvider adapt(ToolCallbackProvider provider, ObjectMapper objectMapper) {
        ToolCallback[] callbacks = provider.getToolCallbacks();
        ToolCallback[] adapted = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            adapted[i] = adapt(callbacks[i], objectMapper);
        }
        return ToolCallbackProvider.from(adapted);
    }

    private static ToolCallback adapt(ToolCallback callback, ObjectMapper objectMapper) {
        ToolDefinition definition = callback.getToolDefinition();
        ToolDefinition adaptedDefinition = ToolDefinition.builder()
                .name(definition.name())
                .description(definition.description())
                .inputSchema(adaptSchema(definition.inputSchema(), objectMapper))
                .build();
        return new DelegatingToolCallback(callback, adaptedDefinition);
    }

    private static String adaptSchema(String inputSchema, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(inputSchema);
            // Pydantic은 enum/중첩 모델을 $defs + $ref로 내보내지만,
            // Vertex Gemini 도구 스키마 변환기는 $defs 자체를 거부한다.
            JsonNode adapted = inlineDefinitions(root, definitions(root), objectMapper, new HashSet<>());
            return objectMapper.writeValueAsString(adapted);
        } catch (JsonProcessingException e) {
            return inputSchema;
        }
    }

    private static JsonNode definitions(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        return root.path("$defs").isObject() ? root.path("$defs") : null;
    }

    private static JsonNode inlineDefinitions(
            JsonNode node,
            JsonNode definitions,
            ObjectMapper objectMapper,
            Set<String> resolving
    ) {
        if (node == null) {
            return objectMapper.nullNode();
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(inlineDefinitions(child, definitions, objectMapper, resolving)));
            return result;
        }
        if (!node.isObject()) {
            return node.deepCopy();
        }

        ObjectNode objectNode = (ObjectNode) node;
        JsonNode refNode = objectNode.get("$ref");
        if (refNode != null && refNode.isTextual()) {
            JsonNode resolved = resolveRef(refNode.asText(), definitions, objectMapper, resolving);
            if (resolved.isObject()) {
                ObjectNode merged = (ObjectNode) resolved.deepCopy();
                // description 같은 sibling 필드는 모델 힌트이므로 ref를 펼친 뒤에도 보존한다.
                mergeSiblingFields(objectNode, merged, definitions, objectMapper, resolving);
                return merged;
            }
            return resolved;
        }

        ObjectNode result = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"$defs".equals(field.getKey())) {
                result.set(field.getKey(), inlineDefinitions(field.getValue(), definitions, objectMapper, resolving));
            }
        }
        return result;
    }

    private static void mergeSiblingFields(
            ObjectNode source,
            ObjectNode target,
            JsonNode definitions,
            ObjectMapper objectMapper,
            Set<String> resolving
    ) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"$ref".equals(field.getKey())) {
                target.set(field.getKey(), inlineDefinitions(field.getValue(), definitions, objectMapper, resolving));
            }
        }
    }

    private static JsonNode resolveRef(
            String ref,
            JsonNode definitions,
            ObjectMapper objectMapper,
            Set<String> resolving
    ) {
        String prefix = "#/$defs/";
        if (definitions == null || !ref.startsWith(prefix)) {
            return objectMapper.createObjectNode();
        }

        String name = ref.substring(prefix.length()).replace("~1", "/").replace("~0", "~");
        if (!resolving.add(name)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode definition = definitions.get(name);
            if (definition == null) {
                return objectMapper.createObjectNode();
            }
            return inlineDefinitions(definition, definitions, objectMapper, resolving);
        } finally {
            resolving.remove(name);
        }
    }

    private record DelegatingToolCallback(
            ToolCallback delegate,
            ToolDefinition toolDefinition
    ) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return callAndRecord(input, () -> delegate.call(input));
        }

        @Override
        public String call(String input, ToolContext toolContext) {
            return callAndRecord(input, () -> delegate.call(input, toolContext));
        }

        private String callAndRecord(String input, ToolCallSupplier supplier) {
            String toolName = originalToolName(delegate).orElse(toolDefinition.name());
            try {
                String output = supplier.call();
                McpToolExecutionRecorder.record(toolName, input, output, false);
                return output;
            } catch (RuntimeException e) {
                McpToolExecutionRecorder.record(toolName, input, e.getMessage(), true);
                throw e;
            }
        }

        private Optional<String> originalToolName(ToolCallback callback) {
            try {
                Method method = callback.getClass().getMethod("getOriginalToolName");
                Object value = method.invoke(callback);
                return value instanceof String name && !name.isBlank()
                        ? Optional.of(name)
                        : Optional.empty();
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }

        @FunctionalInterface
        private interface ToolCallSupplier {

            String call();
        }
    }
}
