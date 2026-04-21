package com.back.domain.adapter.out.persistence.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DB의 'domain' 테이블과 매핑되는 영속성 엔티티
 */
@Getter
@Entity
@Table(name = "domain")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DomainJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    public DomainJpaEntity(String name) {
        this.name = name;
    }
}
