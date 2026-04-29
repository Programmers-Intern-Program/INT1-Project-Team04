package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.domain.adapter.out.persistence.hub.AiDataHubJpaRepository;
import com.back.domain.adapter.out.persistence.hub.AiDataHubPersistenceAdapter;
import com.back.domain.adapter.out.persistence.mcp.McpServerJpaEntity;
import com.back.domain.adapter.out.persistence.mcp.McpServerJpaRepository;
import com.back.domain.adapter.out.persistence.mcp.McpToolJpaEntity;
import com.back.domain.adapter.out.persistence.mcp.McpToolJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.user.User;
import com.back.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Persistence: AI 데이터 허브 영속성 어댑터 테스트")
class AiDataHubPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Autowired
    private McpServerJpaRepository mcpServerJpaRepository;

    @Autowired
    private McpToolJpaRepository mcpToolJpaRepository;

    @Autowired
    private AiDataHubJpaRepository aiDataHubJpaRepository;

    @Autowired
    private AiDataHubPersistenceAdapter aiDataHubPersistenceAdapter;

    @Test
    @DisplayName("Persistence: 임베딩과 메타데이터가 없는(Null) 상태의 AI 데이터 저장 테스트")
    void savesAiDataHubThroughAdapterWithNullableEmbeddingAndMetadata() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("user@example.com", "token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("stock"));
        McpServerJpaEntity server = mcpServerJpaRepository.save(new McpServerJpaEntity("default-mcp", "server", "http://localhost:8090/tools/execute"));
        McpToolJpaEntity tool = mcpToolJpaRepository.save(new McpToolJpaEntity(server, domain, "search_stock_news", "주식 뉴스 조회", "{}"));

        AiDataHub saved = aiDataHubPersistenceAdapter.save(
                new AiDataHub(
                        "11111111-1111-1111-1111-111111111111",
                        new User(user.getId(), user.getEmail(), user.getNickname(), user.getCreatedAt(), user.getDeletedAt()),
                        new McpTool(
                                tool.getId(),
                                new McpServer(server.getId(), server.getName(), server.getDescription(), server.getEndpoint()),
                                new Domain(domain.getId(), domain.getName()),
                                tool.getName(),
                                tool.getDescription(),
                                tool.getInputSchema()
                        ),
                        "NEWS",
                        "content",
                        null,
                        "{\"source\":\"mcp\"}",
                        null
                )
        );

        assertThat(saved.id()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(saved.user().id()).isEqualTo(user.getId());
        assertThat(saved.mcpTool().id()).isEqualTo(tool.getId());
        assertThat(aiDataHubJpaRepository.findById(saved.id())).isPresent();
    }

    @Test
    @DisplayName("Persistence: 사용자와 MCP 도구 기준으로 최근 AI 데이터 허브 스냅샷을 최신순으로 조회한다")
    void loadsRecentAiDataHubByUserAndTool() {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("recent-user@example.com", "token"));
        DomainJpaEntity domain = domainJpaRepository.save(new DomainJpaEntity("real-estate"));
        McpServerJpaEntity server = mcpServerJpaRepository.save(new McpServerJpaEntity("default-mcp", "server", "http://localhost:8090/tools/execute"));
        McpToolJpaEntity tool = mcpToolJpaRepository.save(new McpToolJpaEntity(server, domain, "search_house_price", "부동산 실거래가 조회", "{}"));
        McpToolJpaEntity otherTool = mcpToolJpaRepository.save(new McpToolJpaEntity(server, domain, "search_stock_news", "주식 뉴스 조회", "{}"));

        aiDataHubPersistenceAdapter.save(aiDataHub("11111111-1111-1111-1111-111111111111", user, tool));
        aiDataHubJpaRepository.flush();
        aiDataHubPersistenceAdapter.save(aiDataHub("22222222-2222-2222-2222-222222222222", user, tool));
        aiDataHubJpaRepository.flush();
        aiDataHubPersistenceAdapter.save(aiDataHub("33333333-3333-3333-3333-333333333333", user, tool));
        aiDataHubPersistenceAdapter.save(aiDataHub("99999999-9999-9999-9999-999999999999", user, otherTool));

        List<AiDataHub> recent = aiDataHubPersistenceAdapter.loadRecentByUserIdAndToolId(user.getId(), tool.getId(), 2);

        assertThat(recent).extracting(AiDataHub::id)
                .containsExactly("33333333-3333-3333-3333-333333333333", "22222222-2222-2222-2222-222222222222");
    }

    private AiDataHub aiDataHub(String id, UserJpaEntity user, McpToolJpaEntity tool) {
        return new AiDataHub(
                id,
                new User(user.getId(), user.getEmail(), user.getNickname(), user.getCreatedAt(), user.getDeletedAt()),
                new McpTool(
                        tool.getId(),
                        new McpServer(tool.getServer().getId(), tool.getServer().getName(), tool.getServer().getDescription(), tool.getServer().getEndpoint()),
                        new Domain(tool.getDomain().getId(), tool.getDomain().getName()),
                        tool.getName(),
                        tool.getDescription(),
                        tool.getInputSchema()
                ),
                "REAL_ESTATE",
                "content",
                null,
                "{\"structured\":{\"summary\":{\"avg_deal_amount\":100000},\"query\":{\"lawd_cd\":\"11680\"}},\"metadata\":{}}",
                null
        );
    }
}
