package com.back.domain.application.service;

import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.result.MemberResult;
import com.back.domain.model.user.OAuthProvider;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberService {

    private final UserJpaRepository userJpaRepository;
    private final UserOAuthConnectionJpaRepository connectionRepository;
    private final UserSessionJpaRepository sessionRepository;
    private final SubscriptionJpaRepository subscriptionRepository;
    private final SessionTokenService sessionTokenService;

    @Transactional(readOnly = true)
    public MemberResult get(Long userId) {
        UserJpaEntity user = loadActiveUser(userId);
        return toResult(user);
    }

    public MemberResult updateNickname(Long userId, String nickname) {
        UserJpaEntity user = loadActiveUser(userId);
        user.updateNickname(nickname);
        return toResult(user);
    }

    public void logout(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return;
        }

        sessionRepository.deleteByTokenHash(sessionTokenService.hash(rawSessionToken));
    }

    public void withdraw(Long userId) {
        UserJpaEntity user = loadActiveUser(userId);

        List<SubscriptionJpaEntity> subscriptions = subscriptionRepository.findByUserIdAndActiveTrue(userId);
        subscriptions.forEach(SubscriptionJpaEntity::deactivate);
        connectionRepository.deleteByUserId(userId);
        sessionRepository.deleteByUserId(userId);
        user.withdraw();
    }

    private UserJpaEntity loadActiveUser(Long userId) {
        return userJpaRepository.findById(userId)
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private MemberResult toResult(UserJpaEntity user) {
        List<OAuthProvider> providers = connectionRepository.findByUserId(user.getId())
                .stream()
                .map(UserOAuthConnectionJpaEntity::getProvider)
                .toList();

        return new MemberResult(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                providers
        );
    }
}
