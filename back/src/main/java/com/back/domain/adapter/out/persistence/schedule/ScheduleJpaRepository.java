package com.back.domain.adapter.out.persistence.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [Persistence Adapter의 도구] Schedule 엔티티에 대한 실제 DB 접근을 담당하는 JPA 레포지토리
 */
public interface ScheduleJpaRepository extends JpaRepository<ScheduleJpaEntity, String> {
    List<ScheduleJpaEntity> findByNextRunLessThanEqual(LocalDateTime now);
}
