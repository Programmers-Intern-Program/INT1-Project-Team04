package com.back.domain.application.service;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.result.MemberResult;
import com.back.domain.application.result.OAuthLoginResult;
import com.back.domain.model.user.OAuthUserProfile;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class OAuthLoginService {

    private static final long DEFAULT_SESSION_DAYS = 14;

    private final UserJpaRepository userJpaRepository;
    private final UserOAuthConnectionJpaRepository connectionRepository;
    private final UserSessionJpaRepository sessionRepository;
    private final SessionTokenService sessionTokenService;
    private final MemberService memberService;

    public OAuthLoginResult login(OAuthUserProfile profile) {
        UserJpaEntity user = findOrCreateUser(profile);
        createConnectionIfMissing(user, profile);

        String rawToken = sessionTokenService.createRawToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(DEFAULT_SESSION_DAYS);
        sessionRepository.save(new UserSessionJpaEntity(user, sessionTokenService.hash(rawToken), expiresAt));

        MemberResult member = memberService.get(user.getId());
        return new OAuthLoginResult(member, rawToken, expiresAt);
    }

    private UserJpaEntity findOrCreateUser(OAuthUserProfile profile) {
        return connectionRepository.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .map(UserOAuthConnectionJpaEntity::getUser)
                .filter(user -> user.getDeletedAt() == null)
                .or(() -> userJpaRepository.findByEmailAndDeletedAtIsNull(profile.email()))
                .orElseGet(() -> userJpaRepository.save(new UserJpaEntity(profile.email(), fallbackNickname(profile))));
    }

    private void createConnectionIfMissing(UserJpaEntity user, OAuthUserProfile profile) {
        if (connectionRepository.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId()).isPresent()) {
            return;
        }

        connectionRepository.save(new UserOAuthConnectionJpaEntity(
                user,
                profile.provider(),
                profile.providerUserId(),
                profile.email(),
                profile.accessToken()
        ));
    }

    private String fallbackNickname(OAuthUserProfile profile) {
        if (profile.nickname() != null && !profile.nickname().isBlank()) {
            return profile.nickname();
        }

        return profile.email();
    }
}
