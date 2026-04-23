package com.back.domain.adapter.out.persistence.user;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionJpaRepository extends JpaRepository<UserSessionJpaEntity, Long> {
    Optional<UserSessionJpaEntity> findByTokenHashAndExpiresAtAfter(String tokenHash, LocalDateTime now);

    void deleteByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);
}
