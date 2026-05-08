package com.back.domain.adapter.in.web.admin;

import com.back.domain.application.result.TokenUsageHistoryResult;
import java.util.List;

/**
 * [Response DTO] 최근 토큰 사용 내역
 */
public record RecentTokenHistoryResponse(
        List<TokenHistoryDto> history,
        int totalCount
) {
    public static RecentTokenHistoryResponse from(List<TokenUsageHistoryResult> results) {
        return new RecentTokenHistoryResponse(
                results.stream()
                        .map(TokenHistoryDto::from)
                        .toList(),
                results.size()
        );
    }

    public record TokenHistoryDto(
            String id,
            Long userId,
            String userEmail,
            String userNickname,
            String usageType,
            int amount,
            int balanceBefore,
            int balanceAfter,
            String description,
            String sessionId,
            String createdAt
    ) {
        public static TokenHistoryDto from(TokenUsageHistoryResult result) {
            return new TokenHistoryDto(
                    result.id(),
                    result.userId(),
                    result.userEmail(),
                    result.userNickname(),
                    result.usageType().name(),
                    result.amount(),
                    result.balanceBefore(),
                    result.balanceAfter(),
                    result.description(),
                    result.sessionId(),
                    result.createdAt().toString()
            );
        }
    }
}
