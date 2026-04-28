package com.back.domain.model.token;

import com.back.domain.model.user.User;
import java.time.LocalDateTime;

/**
 * 토큰 사용 내역 도메인
 */
public record TokenUsageHistory(
        String id,
        User user,
        TokenUsageType type,
        int amount,
        int balanceBefore,
        int balanceAfter,
        String description,
        String referenceId,
        LocalDateTime createdAt
) {
    public static TokenUsageHistory create(
            String id,
            User user,
            TokenUsageType type,
            int amount,
            int balanceBefore,
            int balanceAfter,
            String description,
            String referenceId
    ) {
        return new TokenUsageHistory(
                id,
                user,
                type,
                amount,
                balanceBefore,
                balanceAfter,
                description,
                referenceId,
                LocalDateTime.now()
        );
    }
}
