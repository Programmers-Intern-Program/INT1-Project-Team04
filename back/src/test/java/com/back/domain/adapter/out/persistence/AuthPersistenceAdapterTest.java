package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.model.user.OAuthProvider;
import com.back.support.IntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Persistence: OAuth 연결과 세션 테스트")
class AuthPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserOAuthConnectionJpaRepository connectionRepository;

    @Autowired
    private UserSessionJpaRepository sessionRepository;

    @Test
    @DisplayName("provider와 providerUserId로 OAuth 연결을 조회한다")
    void findsConnectionByProviderIdentity() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("user@example.com", "사용자"));
        connectionRepository.save(new UserOAuthConnectionJpaEntity(
                user,
                OAuthProvider.GOOGLE,
                "google-1",
                "user@example.com",
                "provider-token"
        ));

        assertThat(connectionRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-1"))
                .map(connection -> connection.getUser().getId())
                .contains(user.getId());
    }

    @Test
    @DisplayName("만료되지 않은 세션만 토큰 해시로 조회한다")
    void findsValidSessionByHash() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("session@example.com", "세션사용자"));
        sessionRepository.save(new UserSessionJpaEntity(user, "hash-1", LocalDateTime.now().plusDays(7)));
        sessionRepository.save(new UserSessionJpaEntity(user, "hash-expired", LocalDateTime.now().minusDays(1)));

        assertThat(sessionRepository.findByTokenHashAndExpiresAtAfter("hash-1", LocalDateTime.now())).isPresent();
        assertThat(sessionRepository.findByTokenHashAndExpiresAtAfter("hash-expired", LocalDateTime.now())).isEmpty();
    }
}
