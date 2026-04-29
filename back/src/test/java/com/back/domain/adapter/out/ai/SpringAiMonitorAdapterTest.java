package com.back.domain.adapter.out.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class SpringAiMonitorAdapterTest {

    @Test
    void skipsMonitorCallWhenAnthropicApiKeyIsBlank() {
        ChatClient chatClient = mock(ChatClient.class);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, " ");

        adapter.run();

        verifyNoInteractions(chatClient);
    }
}
