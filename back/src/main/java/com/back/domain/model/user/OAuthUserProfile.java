package com.back.domain.model.user;

public record OAuthUserProfile(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String nickname,
        String accessToken
) {
}
