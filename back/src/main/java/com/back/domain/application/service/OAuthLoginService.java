package com.back.domain.application.service;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.command.GrantTokenCommand;
import com.back.domain.application.port.in.TokenManagementUseCase;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.application.result.MemberResult;
import com.back.domain.application.result.OAuthLoginResult;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OAuthLoginService {

    private static final long DEFAULT_SESSION_DAYS = 14;
    private static final int WELCOME_TOKEN_AMOUNT = 100;

    private final UserJpaRepository userJpaRepository;
    private final UserOAuthConnectionJpaRepository connectionRepository;
    private final UserSessionJpaRepository sessionRepository;
    private final SessionTokenService sessionTokenService;
    private final MemberService memberService;
    private final LoadNotificationEndpointPort loadNotificationEndpointPort;
    private final SaveNotificationEndpointPort saveNotificationEndpointPort;
    private final TokenManagementUseCase tokenManagementUseCase;

    public OAuthLoginResult login(OAuthUserProfile profile) {
        UserCreationResult creationResult = findOrCreateUser(profile);
        UserJpaEntity user = creationResult.user;
        
        createConnectionIfMissing(user, profile);
        saveDiscordNotificationEndpointIfNeeded(user, profile);

        // 신규 사용자인 경우 웰컴 토큰 지급
        if (creationResult.isNewUser) {
            grantWelcomeToken(user.getId());
        }

        String rawToken = sessionTokenService.createRawToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(DEFAULT_SESSION_DAYS);
        sessionRepository.save(new UserSessionJpaEntity(user, sessionTokenService.hash(rawToken), expiresAt));

        MemberResult member = memberService.get(user.getId());
        return new OAuthLoginResult(member, rawToken, expiresAt);
    }

    private UserCreationResult findOrCreateUser(OAuthUserProfile profile) {
        // 기존 OAuth 연결 확인
        var existingConnection = connectionRepository.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId());
        if (existingConnection.isPresent()) {
            UserJpaEntity user = existingConnection.get().getUser();
            if (user.getDeletedAt() == null) {
                return new UserCreationResult(user, false);
            }
        }

        // 같은 이메일의 기존 사용자 확인
        var existingUser = userJpaRepository.findByEmailAndDeletedAtIsNull(profile.email());
        if (existingUser.isPresent()) {
            return new UserCreationResult(existingUser.get(), false);
        }

        // 신규 사용자 생성
        log.info("신규 사용자 생성 - email: {}, provider: {}", profile.email(), profile.provider());
        UserJpaEntity newUser = userJpaRepository.save(new UserJpaEntity(profile.email(), fallbackNickname(profile)));
        return new UserCreationResult(newUser, true);
    }

    private void grantWelcomeToken(Long userId) {
        try {
            tokenManagementUseCase.grantToken(new GrantTokenCommand(
                    userId,
                    WELCOME_TOKEN_AMOUNT,
                    "가입 축하 웰컴 토큰"
            ));
            log.info("웰컴 토큰 지급 완료 - userId: {}, amount: {}", userId, WELCOME_TOKEN_AMOUNT);
        } catch (Exception e) {
            // 토큰 지급 실패해도 로그인은 계속 진행
            log.error("웰컴 토큰 지급 실패 - userId: {}, error: {}", userId, e.getMessage(), e);
        }
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

    private void saveDiscordNotificationEndpointIfNeeded(UserJpaEntity user, OAuthUserProfile profile) {
        if (profile.provider() != OAuthProvider.DISCORD) {
            return;
        }

        NotificationEndpoint existingEndpoint = loadNotificationEndpointPort
                .loadEnabledByUserIdAndChannel(user.getId(), NotificationChannel.DISCORD_DM)
                .orElse(null);
        saveNotificationEndpointPort.save(new NotificationEndpoint(
                existingEndpoint == null ? null : existingEndpoint.id(),
                user.getId(),
                NotificationChannel.DISCORD_DM,
                profile.providerUserId(),
                true
        ));
    }

    private String fallbackNickname(OAuthUserProfile profile) {
        if (profile.nickname() != null && !profile.nickname().isBlank()) {
            return profile.nickname();
        }

        return profile.email();
    }

    /**
     * 사용자 생성 결과를 담는 내부 클래스
     */
    private record UserCreationResult(UserJpaEntity user, boolean isNewUser) {
    }
}
