package com.back.domain.adapter.out.persistence.mcp;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * [Persistence Adapter의 도구] MCP 도구(Tool) 정보를 관리하는 JPA 레포지토리
 * 특정 도메인에 종속된 AI 도구의 이름, 설명, 입력 스키마(JSON Schema) 등을 DB에서 조회
 */
public interface McpToolJpaRepository extends JpaRepository<McpToolJpaEntity, Long> {
    Optional<McpToolJpaEntity> findFirstByDomainId(Long domainId);
}
