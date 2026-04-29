package com.back.domain.application.result;

import com.back.domain.model.token.UserToken;
import java.time.LocalDateTime;

/**
 * [Application Result] 사용자 토큰 요약 정보
 */
public record UserTokenSummaryResult(
        Long userId,
        String userEmail,
        String userNickname,
        int balance,
        int totalGranted,
        int totalUsed,
        LocalDateTime lastUpdatedAt,
        LocalDateTime createdAt
) {
    public static UserTokenSummaryResult from(UserToken userToken) {
        return new UserTokenSummaryResult(
                userToken.user().id(),
                userToken.user().email(),
                userToken.user().nickname(),
                userToken.balance(),
                userToken.totalGranted(),
                userToken.totalUsed(),
                userToken.lastUpdatedAt(),
                userToken.createdAt()
        );
    }
}
