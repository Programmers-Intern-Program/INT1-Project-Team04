package com.back.domain.adapter.in.web.token;

import com.back.domain.application.result.UserTokenResult;
import java.time.LocalDateTime;

public record TokenBalanceResponse(
        Long userId,
        int balance,
        int totalGranted,
        int totalUsed,
        LocalDateTime lastUpdatedAt
) {
    public static TokenBalanceResponse from(UserTokenResult result) {
        return new TokenBalanceResponse(
                result.userId(),
                result.balance(),
                result.totalGranted(),
                result.totalUsed(),
                result.lastUpdatedAt()
        );
    }
}
