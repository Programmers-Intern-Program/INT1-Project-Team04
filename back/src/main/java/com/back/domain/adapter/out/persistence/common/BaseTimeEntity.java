package com.back.domain.adapter.out.persistence.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 생성 시간 공통 엔티티 클래스
 * */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
