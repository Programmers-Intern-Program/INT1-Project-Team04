package com.back.domain.adapter.out.persistence.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * [Persistence Adapter의 도구] Domain 정보를 관리하는 JPA 레포지토리
 */
public interface DomainJpaRepository extends JpaRepository<DomainJpaEntity, Long> {
    Optional<DomainJpaEntity> findByName(String name);
}
