package com.back.domain.application.port.in;

import com.back.domain.application.command.GrantTokenCommand;
import com.back.domain.application.command.UseTokenCommand;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenResult;
import java.util.List;

/**
 * [Incoming Port] 토큰 관리 유스케이스
 */
public interface TokenManagementUseCase {
    
    /**
     * 사용자 토큰 잔액 조회
     */
    UserTokenResult getBalance(Long userId);
    
    /**
     * 토큰 사용 (차감)
     */
    UserTokenResult useToken(UseTokenCommand command);
    
    /**
     * 토큰 부여 (충전)
     */
    UserTokenResult grantToken(GrantTokenCommand command);
    
    /**
     * 토큰 사용 내역 조회
     */
    List<TokenUsageHistoryResult> getUsageHistory(Long userId, int limit);
}
