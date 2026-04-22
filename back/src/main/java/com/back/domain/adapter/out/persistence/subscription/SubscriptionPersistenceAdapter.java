package com.back.domain.adapter.out.persistence.subscription;

import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Persistence Adapter] subscription 정보를 DB에 저장하는 어댑터
 * * 비즈니스 규칙이 담긴 'Subscription' 도메인 모델을 받아
 * * JPA를 통해 DB에 반영하고 저장된 결과를 다시 도메인 모델로 변환하여 반환합니다.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionPersistenceAdapter implements SaveSubscriptionPort {

    private final SubscriptionJpaRepository subscriptionJpaRepository;

    @Override
    @Transactional
    public Subscription save(Subscription subscription) {
        SubscriptionJpaEntity saved = subscriptionJpaRepository.save(SubscriptionJpaEntity.from(subscription));

        return new Subscription(
            saved.getId(),
            new User(saved.getUser().getId(), saved.getUser().getEmail(), saved.getUser().getDiscordToken(), saved.getUser().getCreatedAt()),
            new Domain(saved.getDomain().getId(), saved.getDomain().getName()),
            saved.getQuery(),
            saved.isActive(),
            saved.getCreatedAt()
        );
    }
}
