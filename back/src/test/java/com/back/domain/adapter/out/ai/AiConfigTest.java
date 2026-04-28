package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;

class AiConfigTest {

    @Test
    void monitorChatClientRegistersToolCallbackProvider() {
        AiConfig aiConfig = new AiConfig();
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ToolCallbackProvider toolCallbackProvider = ToolCallbackProvider.from();

        assertThatCode(() -> aiConfig.monitorChatClient(builder, Optional.of(toolCallbackProvider)))
                .doesNotThrowAnyException();
    }
}
