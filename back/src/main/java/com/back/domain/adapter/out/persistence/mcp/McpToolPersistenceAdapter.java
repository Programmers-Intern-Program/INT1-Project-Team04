package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [Persistence Adapter] MCP Tool 정보를 로딩하는 어댑터
 * * DB 엔티티들을 조합하여 비즈니스 로직에 필요한 완전한 형태의 'McpTool' 도메인 모델을 생성
 */
@Component
@RequiredArgsConstructor
public class McpToolPersistenceAdapter implements LoadMcpToolPort {

    private final McpToolJpaRepository mcpToolJpaRepository;

    // 내부 객체들은 JPA 영속성 컨텍스트의 영향에 있기 때문에 준영속 상태로 Service 로직에 영향을 줄 수 있으므로
    // 새롭게 생성한 순수한 규칙(도메인 모델)으로 교체하여 사용해야한다
    @Override
    public Optional<McpTool> loadByDomainId(Long domainId) {
        return mcpToolJpaRepository.findFirstByDomainId(domainId)
            .map(entity -> new McpTool(
                entity.getId(),
                new McpServer(
                    entity.getServer().getId(),
                    entity.getServer().getName(),
                    entity.getServer().getDescription(),
                    entity.getServer().getEndpoint()
                ),
                new Domain(entity.getDomain().getId(),
                        entity.getDomain().getName()),
                entity.getName(),
                entity.getDescription(),
                entity.getInputSchema()
            ));
    }
}
