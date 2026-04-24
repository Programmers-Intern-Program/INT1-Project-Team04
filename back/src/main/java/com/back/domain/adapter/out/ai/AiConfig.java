package com.back.domain.adapter.out.ai;

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
    public ChatClient monitorChatClient(ChatModel chatModel, ToolCallbackProvider toolCallbackProvider) {
        // spring-ai-starter-mcp-client가 ToolCallbackProvider를 자동 구성 → MCP tool 자동 연결
        return ChatClient.builder(chatModel)
                .defaultTools(toolCallbackProvider)
                .build();
    }
}
