package com.back.domain.adapter.in.web.admin;

import com.back.domain.application.result.UserTokenSummaryResult;
import java.util.List;

/**
 * [Response DTO] 사용자 토큰 목록
 */
public record UserTokenListResponse(
        List<UserTokenDto> users,
        int page,
        int size,
        int totalElements
) {
    public static UserTokenListResponse from(List<UserTokenSummaryResult> results, int page, int size) {
        return new UserTokenListResponse(
                results.stream()
                        .map(UserTokenDto::from)
                        .toList(),
                page,
                size,
                results.size()
        );
    }

    public record UserTokenDto(
            Long userId,
            String email,
            String nickname,
            int balance,
            int totalGranted,
            int totalUsed,
            String lastUpdatedAt,
            String createdAt
    ) {
        public static UserTokenDto from(UserTokenSummaryResult result) {
            return new UserTokenDto(
                    result.userId(),
                    result.userEmail(),
                    result.userNickname(),
                    result.balance(),
                    result.totalGranted(),
                    result.totalUsed(),
                    result.lastUpdatedAt().toString(),
                    result.createdAt().toString()
            );
        }
    }
}
