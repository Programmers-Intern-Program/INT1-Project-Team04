package com.back.domain.adapter.in.web.admin;

import com.back.domain.application.port.in.TokenAdminUseCase;
import com.back.domain.application.result.TokenStatisticsResult;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenSummaryResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [REST Controller] 토큰 관리자 대시보드 API
 * 
 * TODO: 관리자 인증/인가 추가 필요 (현재 미적용)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tokens")
@RequiredArgsConstructor
public class AdminTokenController {

    private final TokenAdminUseCase tokenAdminUseCase;

    /**
     * 전체 토큰 통계 조회
     * 
     * GET /api/admin/tokens/statistics
     */
    @GetMapping("/statistics")
    public TokenStatisticsResponse getStatistics() {
        log.info("관리자: 전체 토큰 통계 조회 요청");

        TokenStatisticsResult result = tokenAdminUseCase.getStatistics();
        return TokenStatisticsResponse.from(result);
    }

    /**
     * 전체 사용자 토큰 현황 조회 (페이징)
     * 
     * GET /api/admin/tokens/users?page=0&size=20
     */
    @GetMapping("/users")
    public UserTokenListResponse getAllUserTokens(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("관리자: 전체 사용자 토큰 현황 조회 - page: {}, size: {}", page, size);

        List<UserTokenSummaryResult> results = tokenAdminUseCase.getAllUserTokens(page, size);
        return UserTokenListResponse.from(results, page, size);
    }

    /**
     * 최근 토큰 사용 내역 조회 (모든 사용자)
     * 
     * GET /api/admin/tokens/history/recent?limit=100
     */
    @GetMapping("/history/recent")
    public RecentTokenHistoryResponse getRecentHistory(
            @RequestParam(defaultValue = "100") int limit
    ) {
        log.info("관리자: 최근 토큰 사용 내역 조회 - limit: {}", limit);

        List<TokenUsageHistoryResult> results = tokenAdminUseCase.getRecentHistory(limit);
        return RecentTokenHistoryResponse.from(results);
    }
}
