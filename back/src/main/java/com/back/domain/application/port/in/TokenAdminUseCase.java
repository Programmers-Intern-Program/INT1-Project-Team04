package com.back.domain.application.port.in;

import com.back.domain.application.result.TokenStatisticsResult;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenSummaryResult;
import java.util.List;

/**
 * [Incoming Port] 토큰 관리자 유스케이스
 */
public interface TokenAdminUseCase {
    
    /**
     * 전체 토큰 통계 조회
     */
    TokenStatisticsResult getStatistics();
    
    /**
     * 모든 사용자의 토큰 현황 조회
     */
    List<UserTokenSummaryResult> getAllUserTokens(int page, int size);
    
    /**
     * 최근 토큰 사용 내역 조회 (모든 사용자)
     */
    List<TokenUsageHistoryResult> getRecentHistory(int limit);
}
