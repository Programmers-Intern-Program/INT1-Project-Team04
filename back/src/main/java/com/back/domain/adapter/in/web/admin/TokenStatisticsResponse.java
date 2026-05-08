package com.back.domain.adapter.in.web.admin;

import com.back.domain.application.result.TokenStatisticsResult;

/**
 * [Response DTO] 전체 토큰 통계
 */
public record TokenStatisticsResponse(
        long totalUsers,
        long totalTokensGranted,
        long totalTokensUsed,
        long totalTokensRemaining,
        long activeUsers,
        double averageTokensPerUser
) {
    public static TokenStatisticsResponse from(TokenStatisticsResult result) {
        double avgPerUser = result.totalUsers() > 0
                ? (double) result.totalTokensGranted() / result.totalUsers()
                : 0.0;

        return new TokenStatisticsResponse(
                result.totalUsers(),
                result.totalTokensGranted(),
                result.totalTokensUsed(),
                result.totalTokensRemaining(),
                result.activeUsers(),
                Math.round(avgPerUser * 100.0) / 100.0
        );
    }
}
