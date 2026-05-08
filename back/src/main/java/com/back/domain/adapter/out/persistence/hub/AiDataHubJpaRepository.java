package com.back.domain.adapter.out.persistence.hub;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(value = """
            select *
            from ai_data_hub hub
            where hub.user_id = :userId
              and hub.mcp_tool_id = :mcpToolId
              and hub.metadata -> 'execution' ->> 'subscription_id' = :subscriptionId
            order by hub.created_at desc, hub.id desc
            limit :limit
            """, nativeQuery = true)
    List<AiDataHubJpaEntity> findRecentByUserIdAndMcpToolIdAndSubscriptionId(
            @Param("userId") Long userId,
            @Param("mcpToolId") Long mcpToolId,
            @Param("subscriptionId") String subscriptionId,
            @Param("limit") int limit
    );
}
