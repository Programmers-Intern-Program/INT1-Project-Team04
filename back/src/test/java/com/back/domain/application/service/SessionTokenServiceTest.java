package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.model.user.OAuthProvider;
import com.back.global.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 세션 토큰 서비스 테스트")
class SessionTokenServiceTest {

    @Test
    @DisplayName("원본 토큰과 해시는 서로 다르고 같은 토큰은 같은 해시를 만든다")
    void hashesSessionToken() {
        SessionTokenService service = new SessionTokenService();
        String raw = "session-token";

        String first = service.hash(raw);
        String second = service.hash(raw);

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(raw);
        assertThat(first).hasSize(64);
    }

    @Test
    @DisplayName("OAuth provider path 값을 enum으로 변환한다")
    void parsesOAuthProviderPath() {
        assertThat(OAuthProvider.fromPath("kakao")).isEqualTo(OAuthProvider.KAKAO);
        assertThat(OAuthProvider.fromPath("google")).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(OAuthProvider.fromPath("discord")).isEqualTo(OAuthProvider.DISCORD);
        assertThatThrownBy(() -> OAuthProvider.fromPath("github")).isInstanceOf(ApiException.class);
    }
}
