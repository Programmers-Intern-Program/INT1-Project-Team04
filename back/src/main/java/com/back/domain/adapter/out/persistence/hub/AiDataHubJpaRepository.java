package com.back.domain.adapter.out.persistence.hub;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [Persistence Adapter의 도구] AI 데이터 허브(통합 이력 관리) JPA 레포지토리
 * * AI 모델의 응답 결과, 임베딩, 메타데이터 등 시스템에서 생성된 모든
 * * AI 관련 산출물의 영속성을 관리
 */
public interface AiDataHubJpaRepository extends JpaRepository<AiDataHubJpaEntity, String> {

    List<AiDataHubJpaEntity> findByUserId(Long userId);

    List<AiDataHubJpaEntity> findByUserIdAndMcpToolIdOrderByCreatedAtDescIdDesc(
            Long userId,
            Long mcpToolId,
            Pageable pageable
    );
}
