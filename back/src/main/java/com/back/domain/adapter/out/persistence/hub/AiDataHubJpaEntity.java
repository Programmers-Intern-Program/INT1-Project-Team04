package com.back.domain.adapter.out.persistence.hub;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.adapter.out.persistence.mcp.McpToolJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.model.hub.AiDataHub;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * [Persistence Entity] AI 데이터 허브 테이블과 매핑되는 엔티티
 * * 사용자(User)와 MCP 도구 간의 상호작용 데이터, AI 생성 콘텐츠,
 * * 그리고 벡터 검색을 위한 임베딩 데이터를 통합 관리한다
 */
@Getter
@Entity
@Table(name = "ai_data_hub")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiDataHubJpaEntity extends BaseTimeEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserJpaEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mcp_tool_id")
    private McpToolJpaEntity mcpTool;

    @Column(name = "api_type", nullable = false, length = 50)
    private String apiType;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String metadata;

    public AiDataHubJpaEntity(String id,
                              UserJpaEntity user,
                              McpToolJpaEntity mcpTool,
                              String apiType,
                              String content,
                              String embedding,
                              String metadata) {
        this.id = id;
        this.user = user;
        this.mcpTool = mcpTool;
        this.apiType = apiType;
        this.content = content;
        this.embedding = embedding;
        this.metadata = metadata;
    }

    public static AiDataHubJpaEntity from(AiDataHub aiDataHub) {
        return new AiDataHubJpaEntity(
                aiDataHub.id(),
                aiDataHub.user() == null ? null : UserJpaEntity.from(aiDataHub.user()),
                aiDataHub.mcpTool() == null ? null : McpToolJpaEntity.from(aiDataHub.mcpTool()),
                aiDataHub.apiType(),
                aiDataHub.content(),
                aiDataHub.embedding(),
                aiDataHub.metadata()
        );
    }

    public AiDataHub toDomain() {
        return new AiDataHub(
                id,
                user == null ? null : user.toDomain(),
                mcpTool == null ? null : mcpTool.toDomain(),
                apiType,
                content,
                embedding,
                metadata,
                getCreatedAt()
        );
    }
}
