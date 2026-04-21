package com.back.domain.adapter.out.persistence.mcp;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * MCP 서버 정보를 저장하는 영속성 엔티티
 * 외부 AI 도구들이 참조할 서버의 명칭, 설명 및 접속 엔드포인트를 관리
 */
@Getter
@Entity
@Table(name = "mcp_server")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpServerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    public McpServerJpaEntity(String name,
                              String description,
                              String endpoint) {
        this.name = name;
        this.description = description;
        this.endpoint = endpoint;
    }
}
