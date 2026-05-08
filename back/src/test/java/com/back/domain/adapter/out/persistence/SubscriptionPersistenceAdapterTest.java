package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;


@Transactional
@DisplayName("Persistence: 구독 정보 저장 테스트")
class SubscriptionPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    @Test
    @DisplayName("성공: 사용자(User)와 도메인(Domain) 정보를 포함하여 구독 정보를 정상적으로 저장한다")
    void savesSubscriptionWithUserAndDomain() {
        // [Given] 테스트를 위한 기초 데이터(사용자, 도메인)를 먼저 영속화함
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("user@example.com", "token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("real-estate"));

        // [When] 구독(Subscription) 엔티티를 생성하고 저장함
        SubscriptionJpaEntity subscription = subscriptionJpaRepository.save(
                new SubscriptionJpaEntity(user, domain, "강남구 아파트 실거래가", "create", true)
        );

        // [Then] 저장된 데이터의 무결성을 검증함
        assertThat(subscription.getId()).as("구독 ID는 생성 시 자동으로 부여되어야 한다").isNotBlank();
        assertThat(subscription.getUser().getId()).as("연결된 사용자 ID가 일치해야 한다").isEqualTo(user.getId());
        assertThat(subscription.getDomain().getId()).as("연결된 도메인 ID가 일치해야 한다").isEqualTo(domain.getId());
        assertThat(subscription.isActive()).as("구독은 기본적으로 활성화 상태여야 한다").isTrue();
    }
}
