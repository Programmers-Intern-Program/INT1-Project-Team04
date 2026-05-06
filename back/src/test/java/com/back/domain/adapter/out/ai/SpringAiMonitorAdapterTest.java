package com.back.domain.adapter.out.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class SpringAiMonitorAdapterTest {

    @Test
    void skipsMonitorCallWhenChatClientIsNull() {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(null, new ObjectMapper(), 5, 10);

        adapter.run();
        // null chatClient → 스킵, 예외 없음
    }

    @Test
    void skipsExecuteWhenChatClientIsNull() {
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(null, new ObjectMapper(), 5, 10);

        adapter.execute(java.util.List.of());
        // null chatClient → 스킵, 예외 없음
    }

    @Test
    void skipsMonitorCallWhenSubscriptionListIsEmpty() {
        ChatClient chatClient = mock(ChatClient.class);
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), 5, 10);

        adapter.execute(java.util.List.of());

        verifyNoInteractions(chatClient);
    }
}
