package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class AiGatewayMonitoringBriefingAdapter implements GenerateMonitoringBriefingPort {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final boolean configured;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final ObjectMapper objectMapper;

    public AiGatewayMonitoringBriefingAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${ai-gateway.base-url:}") String baseUrl,
            @Value("${ai-gateway.api-key:}") String apiKey,
            @Value("${ai-gateway.model:glm-4.5}") String model,
            @Value("${ai-gateway.max-tokens:2048}") int maxTokens,
            @Value("${ai-gateway.temperature:0.3}") double temperature,
            ObjectMapper objectMapper
    ) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        this.configured = !isBlank(normalizedBaseUrl) && !isBlank(apiKey);
        this.restClient = configured
                ? restClientBuilder
                        .baseUrl(normalizedBaseUrl)
                        .defaultHeader("Authorization", "Bearer " + apiKey)
                        .defaultHeader("Content-Type", "application/json")
                        .build()
                : null;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> generate(MonitoringBriefingRequest request) {
        if (!configured) {
            return Optional.empty();
        }
        try {
            String content = callGateway(request);
            return MonitoringBriefingResponse.parse(objectMapper, content)
                    .map(MonitoringBriefingResponse::toMessage);
        } catch (Exception exception) {
            log.warn("[AiGatewayMonitoringBriefingAdapter] AI 브리핑 생성 실패, 기본 변화 알림으로 대체합니다.", exception);
            return Optional.empty();
        }
    }

    private String callGateway(MonitoringBriefingRequest request) throws Exception {
        GlmApiDto.Request gatewayRequest = new GlmApiDto.Request(
                model,
                List.of(
                        new GlmApiDto.Message("system", MonitoringBriefingPromptTemplate.SYSTEM_PROMPT),
                        new GlmApiDto.Message("user", MonitoringBriefingPromptTemplate.userPrompt(request))
                ),
                maxTokens,
                temperature
        );
        String responseBody = restClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .body(gatewayRequest)
                .retrieve()
                .body(String.class);
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (contentNode.isMissingNode()) {
            return "";
        }
        return contentNode.asText().trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(CHAT_COMPLETIONS_PATH)) {
            return normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_PATH.length());
        }
        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
