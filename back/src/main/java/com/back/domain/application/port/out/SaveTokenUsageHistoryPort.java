package com.back.domain.application.port.out;

import com.back.domain.model.token.TokenUsageHistory;
import java.util.List;

/**
 * [Outgoing Port] 토큰 사용 내역 저장 및 조회
 */
public interface SaveTokenUsageHistoryPort {
    TokenUsageHistory save(TokenUsageHistory history);
    
    List<TokenUsageHistory> findByUserId(Long userId, int limit);
}
