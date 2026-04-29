package com.back.domain.application.port.out;

import com.back.domain.model.token.UserToken;
import java.util.List;

/**
 * [Outgoing Port] 토큰 통계 조회
 */
public interface LoadTokenStatisticsPort {
    
    /**
     * 모든 사용자 토큰 조회 (페이징)
     */
    List<UserToken> loadAllUserTokens(int page, int size);
    
    /**
     * 총 부여된 토큰 합계
     */
    long getTotalGranted();
    
    /**
     * 총 사용된 토큰 합계
     */
    long getTotalUsed();
    
    /**
     * 총 잔액 합계
     */
    long getTotalBalance();
    
    /**
     * 활성 사용자 수 (잔액 > 0)
     */
    long getActiveUserCount();
    
    /**
     * 전체 사용자 수
     */
    long getTotalUserCount();
}
