package com.back.domain.adapter.out.persistence.mcp;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * [Persistence Adapter의 도구] MCP 도구(Tool) 정보를 관리하는 JPA 레포지토리
 * 특정 도메인에 종속된 AI 도구의 이름, 설명, 입력 스키마(JSON Schema) 등을 DB에서 조회
 *
 * server/domain 연관은 LAZY 인데 어댑터 호출 측이 트랜잭션 밖이라 toDomain() 변환 중
 * 프록시 초기화가 LazyInitializationException 으로 깨진다. 두 쿼리 모두 @EntityGraph 로
 * server·domain 을 즉시 함께 로딩한다.
 */
public interface McpToolJpaRepository extends JpaRepository<McpToolJpaEntity, Long> {
    @EntityGraph(attributePaths = {"server", "domain"})
    Optional<McpToolJpaEntity> findFirstByDomainId(Long domainId);

    @EntityGraph(attributePaths = {"server", "domain"})
    Optional<McpToolJpaEntity> findFirstByDomainIdAndName(Long domainId, String name);
}
