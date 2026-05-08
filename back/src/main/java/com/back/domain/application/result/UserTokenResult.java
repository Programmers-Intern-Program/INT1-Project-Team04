package com.back.domain.application.result;

import com.back.domain.model.token.UserToken;
import java.time.LocalDateTime;

/**
 * [Application Result] 사용자 토큰 정보 결과
 */
public record UserTokenResult(
        Long userId,
        int balance,
        int totalGranted,
        int totalUsed,
        LocalDateTime lastUpdatedAt
) {
    public static UserTokenResult from(UserToken userToken) {
        return new UserTokenResult(
                userToken.user().id(),
                userToken.balance(),
                userToken.totalGranted(),
                userToken.totalUsed(),
                userToken.lastUpdatedAt()
        );
    }
}
