package com.back.domain.model.user;

import java.time.LocalDateTime;

/**
 * User 도메인
 */
public record User(
        Long id,
        String email,
        String discordToken,
        LocalDateTime createdAt
) {
}
