package com.back.domain.application.result;

import java.time.LocalDateTime;

public record OAuthLoginResult(
        MemberResult member,
        String rawSessionToken,
        LocalDateTime expiresAt
) {
}
