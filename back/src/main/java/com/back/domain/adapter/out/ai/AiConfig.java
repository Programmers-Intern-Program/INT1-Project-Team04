package com.back.domain.adapter.out.ai;

import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key")
public class AiConfig {

    @Bean
    public ChatClient monitorChatClient(ChatModel chatModel, Optional<ToolCallbackProvider> toolCallbackProvider) {
        // TEST : spring.ai.mcp.client.enabled: false로 MCP를 비활성화하자 ToolCallbackProvider 빈이 사라짐. Optional로 변경

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        toolCallbackProvider.ifPresent(builder::defaultTools);
        return builder.build();
    }
}
