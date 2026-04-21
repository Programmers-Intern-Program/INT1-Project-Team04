package com.back.domain.adapter.out.persistence.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [Persistence Adapter의 도구] DB와 실제로 통신하는 JPA 레포지토리
 */
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {
    Optional<UserJpaEntity> findByEmail(String email);
}
