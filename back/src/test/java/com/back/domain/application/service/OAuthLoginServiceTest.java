package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.application.result.OAuthLoginResult;
import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import com.back.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Application: OAuth 로그인 서비스 테스트")
class OAuthLoginServiceTest extends IntegrationTestBase {

    @Autowired
    private OAuthLoginService oauthLoginService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserOAuthConnectionJpaRepository connectionRepository;

    @Test
    @DisplayName("새 provider 계정이면 사용자를 만들고 세션을 만든다")
    void createsUserAndSessionForNewProviderAccount() {
        OAuthLoginResult result = oauthLoginService.login(new OAuthUserProfile(
                OAuthProvider.GOOGLE,
                "google-1",
                "new@example.com",
                "새사용자",
                "provider-token"
        ));

        assertThat(result.rawSessionToken()).isNotBlank();
        assertThat(result.member().email()).isEqualTo("new@example.com");
        assertThat(userJpaRepository.findByEmailAndDeletedAtIsNull("new@example.com")).isPresent();
        assertThat(connectionRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-1")).isPresent();
    }

    @Test
    @DisplayName("같은 이메일의 활성 사용자가 있으면 새 provider를 기존 사용자에 연결한다")
    void mergesByEmail() {
        UserJpaEntity existing = userJpaRepository.save(new UserJpaEntity("same@example.com", "기존사용자"));

        OAuthLoginResult result = oauthLoginService.login(new OAuthUserProfile(
                OAuthProvider.KAKAO,
                "kakao-1",
                "same@example.com",
                "카카오사용자",
                "provider-token"
        ));

        assertThat(result.member().id()).isEqualTo(existing.getId());
        assertThat(connectionRepository.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "kakao-1")).isPresent();
    }

    @Test
    @DisplayName("기존 provider 연결이 있으면 같은 사용자를 재사용한다")
    void reusesExistingProviderConnection() {
        UserJpaEntity existing = userJpaRepository.save(new UserJpaEntity("linked@example.com", "연결사용자"));
        connectionRepository.save(new UserOAuthConnectionJpaEntity(
                existing,
                OAuthProvider.DISCORD,
                "discord-1",
                "linked@example.com",
                "old-token"
        ));

        OAuthLoginResult result = oauthLoginService.login(new OAuthUserProfile(
                OAuthProvider.DISCORD,
                "discord-1",
                "linked@example.com",
                "연결사용자",
                "new-token"
        ));

        assertThat(result.member().id()).isEqualTo(existing.getId());
    }
}
