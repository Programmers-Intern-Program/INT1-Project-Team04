package com.back.domain.application.command;

/**
 * [Application Command] 토큰 사용 명령
 */
public record UseTokenCommand(
        Long userId,
        int amount,
        String description,
        String referenceId
) {
}
