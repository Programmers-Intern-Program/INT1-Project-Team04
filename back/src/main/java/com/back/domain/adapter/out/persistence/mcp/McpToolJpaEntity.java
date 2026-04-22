package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.model.mcp.McpTool;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * MCP 서버가 제공하는 Tool 정보를 저장하는 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "mcp_tool")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpToolJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private McpServerJpaEntity server;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "domain_id", nullable = false)
    private DomainJpaEntity domain;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "input_schema", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String inputSchema;

    public McpToolJpaEntity(McpServerJpaEntity server,
                            DomainJpaEntity domain,
                            String name,
                            String description,
                            String inputSchema) {
        this.server = server;
        this.domain = domain;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public static McpToolJpaEntity from(McpTool tool) {
        McpToolJpaEntity entity = new McpToolJpaEntity(
                McpServerJpaEntity.from(tool.server()),
                DomainJpaEntity.from(tool.domain()),
                tool.name(),
                tool.description(),
                tool.inputSchema());
        entity.id = tool.id();
        return entity;
    }

    public McpTool toDomain() {
        return new McpTool(
                id,
                server.toDomain(),
                domain.toDomain(),
                name,
                description,
                inputSchema);
    }
}
