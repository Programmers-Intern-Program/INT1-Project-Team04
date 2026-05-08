package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.result.MemberResult;
import com.back.domain.model.user.OAuthProvider;
import com.back.support.IntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Application: 회원 서비스 테스트")
class MemberServiceTest extends IntegrationTestBase {

    @Autowired
    private MemberService memberService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    @Autowired
    private UserOAuthConnectionJpaRepository connectionRepository;

    @Autowired
    private UserSessionJpaRepository sessionRepository;

    @Test
    @DisplayName("닉네임만 수정한다")
    void updatesNicknameOnly() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("member@example.com", "이전닉네임"));

        MemberResult result = memberService.updateNickname(user.getId(), "새닉네임");

        assertThat(result.nickname()).isEqualTo("새닉네임");
        assertThat(userJpaRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo("member@example.com");
    }

    @Test
    @DisplayName("탈퇴하면 개인정보와 연결과 세션을 제거하고 구독을 비활성화한다")
    void withdrawsMember() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("withdraw@example.com", "탈퇴대상"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("withdraw-domain"));
        SubscriptionJpaEntity subscription = subscriptionJpaRepository.save(
                new SubscriptionJpaEntity(user, domain, "탈퇴 테스트", "create", true)
        );
        connectionRepository.save(new UserOAuthConnectionJpaEntity(
                user,
                OAuthProvider.GOOGLE,
                "google-w",
                "withdraw@example.com",
                "token"
        ));
        sessionRepository.save(new UserSessionJpaEntity(user, "hash-w", LocalDateTime.now().plusDays(7)));

        memberService.withdraw(user.getId());

        UserJpaEntity withdrawn = userJpaRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getNickname()).isNull();
        assertThat(withdrawn.getDeletedAt()).isNotNull();
        assertThat(connectionRepository.findByUserId(user.getId())).isEmpty();
        assertThat(sessionRepository.findByTokenHashAndExpiresAtAfter("hash-w", LocalDateTime.now())).isEmpty();
        assertThat(subscriptionJpaRepository.findById(subscription.getId()).orElseThrow().isActive()).isFalse();
    }
}
