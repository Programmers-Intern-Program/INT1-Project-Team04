package com.back.domain.adapter.in.web.token;

import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.model.token.TokenUsageType;
import java.time.LocalDateTime;
import java.util.List;

public record TokenUsageHistoryResponse(
        List<TokenUsageHistoryDto> history
) {
    public static TokenUsageHistoryResponse from(List<TokenUsageHistoryResult> results) {
        List<TokenUsageHistoryDto> dtos = results.stream()
                .map(TokenUsageHistoryDto::from)
                .toList();
        return new TokenUsageHistoryResponse(dtos);
    }
}

record TokenUsageHistoryDto(
        String id,
        TokenUsageType type,
        int amount,
        int balanceBefore,
        int balanceAfter,
        String description,
        LocalDateTime createdAt
) {
    static TokenUsageHistoryDto from(TokenUsageHistoryResult result) {
        return new TokenUsageHistoryDto(
                result.id(),
                result.usageType(),
                result.amount(),
                result.balanceBefore(),
                result.balanceAfter(),
                result.description(),
                result.createdAt()
        );
    }
}
