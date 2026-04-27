package com.back.domain.adapter.out.ai;

import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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

        toolCallbackProvider.ifPresent(builder::defaultTools);

        // Spring이 세팅해둔 관찰성(Langfuse 등)이 포함된 빌더를 그대로 빌드합니다.
        return builder.build();
    }
}
