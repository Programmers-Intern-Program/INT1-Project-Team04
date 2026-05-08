package com.back.domain.adapter.out.persistence.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [Persistence Adapter의 도구] Domain 정보를 관리하는 JPA 레포지토리
 */
public interface DomainJpaRepository extends JpaRepository<DomainJpaEntity, Long> {
    Optional<DomainJpaEntity> findByName(String name);

    List<DomainJpaEntity> findAllByOrderByIdAsc();
}
