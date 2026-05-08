package com.back.domain.application.service;

import com.back.domain.adapter.out.persistence.token.TokenUsageHistoryPersistenceAdapter;
import com.back.domain.application.port.in.TokenAdminUseCase;
import com.back.domain.application.port.out.LoadTokenStatisticsPort;
import com.back.domain.application.result.TokenStatisticsResult;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenSummaryResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Application Service] 토큰 관리자 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenAdminService implements TokenAdminUseCase {

    private final LoadTokenStatisticsPort loadTokenStatisticsPort;
    private final TokenUsageHistoryPersistenceAdapter historyAdapter;

    @Override
    @Transactional(readOnly = true)
    public TokenStatisticsResult getStatistics() {
        log.info("전체 토큰 통계 조회");

        long totalUsers = loadTokenStatisticsPort.getTotalUserCount();
        long totalGranted = loadTokenStatisticsPort.getTotalGranted();
        long totalUsed = loadTokenStatisticsPort.getTotalUsed();
        long totalRemaining = loadTokenStatisticsPort.getTotalBalance();
        long activeUsers = loadTokenStatisticsPort.getActiveUserCount();

        return TokenStatisticsResult.of(
                totalUsers,
                totalGranted,
                totalUsed,
                totalRemaining,
                activeUsers
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserTokenSummaryResult> getAllUserTokens(int page, int size) {
        log.info("전체 사용자 토큰 현황 조회 - page: {}, size: {}", page, size);

        return loadTokenStatisticsPort.loadAllUserTokens(page, size)
                .stream()
                .map(UserTokenSummaryResult::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TokenUsageHistoryResult> getRecentHistory(int limit) {
        log.info("최근 토큰 사용 내역 조회 - limit: {}", limit);

        return historyAdapter.findRecentHistory(limit)
                .stream()
                .map(TokenUsageHistoryResult::from)
                .toList();
    }
}
