package com.back.domain.adapter.out.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * GLM API 요청/응답 DTO
 */
public final class GlmApiDto {

    private GlmApiDto() {}

    public record Request(
            String model,
            List<Message> messages,
            @JsonProperty("max_tokens")
            int maxTokens,
            double temperature
    ) {}

    public record Message(
            String role,
            String content
    ) {}

    public record Response(
            List<Choice> choices
    ) {}

    public record Choice(
            ResponseMessage message
    ) {}

    public record ResponseMessage(
            String content
    ) {}
}
