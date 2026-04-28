package com.back.domain.application.port.out;

import com.back.domain.model.token.UserToken;
import java.util.Optional;

/**
 * [Outgoing Port] 사용자 토큰 조회
 */
public interface LoadUserTokenPort {
    Optional<UserToken> loadByUserId(Long userId);
}
