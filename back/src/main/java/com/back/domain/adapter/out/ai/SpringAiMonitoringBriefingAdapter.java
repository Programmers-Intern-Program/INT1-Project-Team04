package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpringAiMonitoringBriefingAdapter implements GenerateMonitoringBriefingPort {

    @Nullable
    private final ChatClient monitorChatClient;
    private final String anthropicApiKey;
    private final ObjectMapper objectMapper;

    public SpringAiMonitoringBriefingAdapter(
            @Autowired(required = false) ChatClient monitorChatClient,
            @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
            ObjectMapper objectMapper
    ) {
        this.monitorChatClient = monitorChatClient;
        this.anthropicApiKey = anthropicApiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> generate(MonitoringBriefingRequest request) {
        if (monitorChatClient == null || isBlank(anthropicApiKey)) {
            return Optional.empty();
        }
        try {
            String content = monitorChatClient.prompt()
                    .system(MonitoringBriefingPromptTemplate.SYSTEM_PROMPT)
                    .user(MonitoringBriefingPromptTemplate.userPrompt(request))
                    .call()
                    .content();
            return MonitoringBriefingResponse.parse(objectMapper, content)
                    .map(MonitoringBriefingResponse::toMessage);
        } catch (Exception exception) {
            log.warn("[SpringAiMonitoringBriefingAdapter] AI 브리핑 생성 실패, 기본 변화 알림으로 대체합니다.", exception);
            return Optional.empty();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
