package com.back.domain.model.subscription;

import com.back.domain.model.domain.Domain;
import com.back.domain.model.user.User;

import java.time.LocalDateTime;

/**
 * 구독 도메인
 */
public record Subscription(
        String id,
        User user,
        Domain domain,
        String query,
        boolean active,
        LocalDateTime createdAt
) {
}
