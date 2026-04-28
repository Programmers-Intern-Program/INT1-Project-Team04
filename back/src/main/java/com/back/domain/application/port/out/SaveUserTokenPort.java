package com.back.domain.application.port.out;

import com.back.domain.model.token.UserToken;

/**
 * [Outgoing Port] 사용자 토큰 저장
 */
public interface SaveUserTokenPort {
    UserToken save(UserToken userToken);
}
