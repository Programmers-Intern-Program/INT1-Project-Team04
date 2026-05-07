package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;

class AiConfigTest {

    @Test
    @DisplayName("모니터 ChatClient에 MCP 도구 콜백을 등록한다")
    void monitorChatClientRegistersToolCallbackProvider() {
        AiConfig aiConfig = new AiConfig();
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ToolCallbackProvider toolCallbackProvider = ToolCallbackProvider.from();

        assertThatCode(() -> aiConfig.monitorChatClient(builder, Optional.of(toolCallbackProvider), new ObjectMapper()))
                .doesNotThrowAnyException();
    }
}
