package com.back.domain.application.result;

/**
 * [Application Result] 전체 토큰 통계
 */
public record TokenStatisticsResult(
        long totalUsers,
        long totalTokensGranted,
        long totalTokensUsed,
        long totalTokensRemaining,
        long activeUsers
) {
    public static TokenStatisticsResult of(
            long totalUsers,
            long totalTokensGranted,
            long totalTokensUsed,
            long totalTokensRemaining,
            long activeUsers
    ) {
        return new TokenStatisticsResult(
                totalUsers,
                totalTokensGranted,
                totalTokensUsed,
                totalTokensRemaining,
                activeUsers
        );
    }
}
