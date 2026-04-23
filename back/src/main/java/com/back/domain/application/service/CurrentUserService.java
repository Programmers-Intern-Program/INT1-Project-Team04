package com.back.domain.application.service;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentUserService {

    private final UserSessionJpaRepository sessionRepository;
    private final SessionTokenService sessionTokenService;

    public UserJpaEntity requireCurrentUser(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        UserJpaEntity user = sessionRepository
                .findByTokenHashAndExpiresAtAfter(sessionTokenService.hash(rawSessionToken), LocalDateTime.now())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED))
                .getUser();

        if (user.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        return user;
    }
}
