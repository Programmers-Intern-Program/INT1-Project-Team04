package com.back.domain.adapter.out.ai;

// TODO: 빌드 후 정확한 import 경로 확인 필요 (Spring AI 2.0.0-M4 기준)
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.client.McpSyncClient;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key", matchIfMissing = false)
public class AiConfig {

    @Bean
    public ChatClient monitorChatClient(ChatModel chatModel, List<McpSyncClient> mcpClients) {
        // MCP 서버에 등록된 tool들이 자동으로 ChatClient에 연결됨
        return ChatClient.builder(chatModel)
                .defaultTools(SyncMcpToolCallbackProvider.from(mcpClients))
                .build();
    }
}
