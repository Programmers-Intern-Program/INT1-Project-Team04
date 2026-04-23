package com.back.domain.model.user;

import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.Locale;

public enum OAuthProvider {
    KAKAO,
    GOOGLE,
    DISCORD;

    public static OAuthProvider fromPath(String path) {
        try {
            return OAuthProvider.valueOf(path.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(ErrorCode.INVALID_OAUTH_PROVIDER);
        }
    }
}
