package com.back.domain.application.command;

/**
 * [Application Command] 멀티 턴 후속 응답 커맨드
 */
public record ContinueParseCommand(
        Long userId,
        String sessionId,
        String response
) {
}
