package com.back.domain.application.result;

import com.back.domain.model.token.TokenUsageHistory;
import com.back.domain.model.token.TokenUsageType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [Application Result] 토큰 사용 내역 결과
 */
public record TokenUsageHistoryResult(
        String id,
        Long userId,
        String userEmail,
        String userNickname,
        TokenUsageType usageType,
        int amount,
        int balanceBefore,
        int balanceAfter,
        String description,
        String sessionId,
        LocalDateTime createdAt
) {
    public static TokenUsageHistoryResult from(TokenUsageHistory history) {
        return new TokenUsageHistoryResult(
                history.id(),
                history.user().id(),
                history.user().email(),
                history.user().nickname(),
                history.type(),
                history.amount(),
                history.balanceBefore(),
                history.balanceAfter(),
                history.description(),
                history.referenceId(),
                history.createdAt()
        );
    }

    public static List<TokenUsageHistoryResult> fromList(List<TokenUsageHistory> histories) {
        return histories.stream()
                .map(TokenUsageHistoryResult::from)
                .toList();
    }
}
