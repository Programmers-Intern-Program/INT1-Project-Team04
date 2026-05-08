package com.back.domain.adapter.in.web.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev")
@RestController
@RequestMapping("/api/ai")
public class AiPingController {

    @Nullable
    private final ChatClient monitorChatClient;

    public AiPingController(@Autowired(required = false) ChatClient monitorChatClient) {
        this.monitorChatClient = monitorChatClient;
    }

    @GetMapping("/ping")
    public PingResponse ping(@RequestParam(defaultValue = "안녕! 한 문장으로 대답해줘.") String message) {
        if (monitorChatClient == null) {
            return new PingResponse("SKIPPED", "ChatClient 미구성 — ANTHROPIC_API_KEY를 확인하세요.");
        }
        String reply = monitorChatClient.prompt()
                .user(message)
                .call()
                .content();
        return new PingResponse("OK", reply);
    }

    record PingResponse(String status, String reply) {}
}
