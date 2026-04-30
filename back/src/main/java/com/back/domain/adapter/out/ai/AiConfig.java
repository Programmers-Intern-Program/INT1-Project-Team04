package com.back.domain.adapter.out.ai;

import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!test")
@Configuration
public class AiConfig {

    @Bean
    public ChatClient monitorChatClient(ChatClient.Builder builder,
                                        Optional<ToolCallbackProvider> toolCallbackProvider) {

        toolCallbackProvider.ifPresent(builder::defaultToolCallbacks);

        // Spring이 세팅해둔 관찰성(Langfuse 등)이 포함된 빌더를 그대로 빌드합니다.
        return builder.build();
    }

    // ChatClient.Builder는 prototype-scoped이므로 여기서 받는 builder는 monitorChatClient와 별개 인스턴스.
    // MCP tool callback이 붙지 않은 순수한 클라이언트 → 자연어 파싱 전용 (JSON만 응답하면 됨)
    @Bean
    public ChatClient parserChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
