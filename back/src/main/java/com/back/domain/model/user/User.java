package com.back.domain.model.user;

import java.time.LocalDateTime;

/**
 * User 도메인
 */
public record User(
        Long id,
        String email,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime deletedAt
) {
    public boolean isActive() {
        return deletedAt == null;
    }
}
