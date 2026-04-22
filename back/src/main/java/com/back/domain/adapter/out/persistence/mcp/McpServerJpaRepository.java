package com.back.domain.adapter.out.persistence.mcp;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * [Persistence Adapter의 도구] MCP 서버 정보를 관리하는 JPA 레포지토리
 * AI 도구들이 실제로 구동되는 물리적/논리적 서버 설정 정보를 DB에서 조회
 */
public interface McpServerJpaRepository extends JpaRepository<McpServerJpaEntity, Long> {
    Optional<McpServerJpaEntity> findByName(String name);
}
