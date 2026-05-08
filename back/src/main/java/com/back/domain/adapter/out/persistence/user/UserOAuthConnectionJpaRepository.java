package com.back.domain.adapter.out.persistence.user;

import com.back.domain.model.user.OAuthProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserOAuthConnectionJpaRepository extends JpaRepository<UserOAuthConnectionJpaEntity, Long> {
    Optional<UserOAuthConnectionJpaEntity> findByProviderAndProviderUserId(
            OAuthProvider provider,
            String providerUserId
    );

    List<UserOAuthConnectionJpaEntity> findByUserId(Long userId);

    Optional<UserOAuthConnectionJpaEntity> findFirstByUserIdAndProvider(Long userId, OAuthProvider provider);

    void deleteByUserId(Long userId);
}
