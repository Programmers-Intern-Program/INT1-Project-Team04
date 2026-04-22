package com.back.domain.adapter.out.persistence.subscription;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [Persistence Adapter의 도구] Subscription 엔티티에 대한 실제 DB 접근을 담당하는 JPA 레포지토리
 * * 사용자의 구독 설정 저장, 조회 및 상태 관리를 수행
 */
public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionJpaEntity, String> {

    List<SubscriptionJpaEntity> findByUserIdAndActiveTrue(Long userId);
}
