package com.back.domain.adapter.out.persistence.subscriptionconversation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionConversationJpaRepository
        extends JpaRepository<SubscriptionConversationJpaEntity, String> {

    Optional<SubscriptionConversationJpaEntity> findByIdAndUserId(String id, Long userId);
}
