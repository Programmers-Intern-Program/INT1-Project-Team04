package com.back.domain.application.command;

/**
 * [Application Command] 토큰 부여 명령
 */
public record GrantTokenCommand(
        Long userId,
        int amount,
        String description
) {
}
