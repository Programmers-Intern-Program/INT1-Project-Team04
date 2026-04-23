package com.back.domain.application.result;

public record McpExecutionResult(
        String apiType,
        String content,
        String metadata
) {
}
