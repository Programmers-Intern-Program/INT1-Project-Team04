package com.back.domain.application.command;

/**
 * [Application Command] 자연어 파싱 요청 커맨드
 */
public record ParseTaskCommand(
        Long userId,
        String input
) {
}
