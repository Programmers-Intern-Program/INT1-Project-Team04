package com.back.domain.adapter.out.persistence.schedule;

import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.model.schedule.Schedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [Persistence Adapter] 스케줄 정보를 DB에 영구 저장하는 어댑터
 * * 도메인 모델과 JPA Entity 간의 변환을 통해 데이터 영속성을 관리
 */
@Component
@RequiredArgsConstructor
public class SchedulePersistenceAdapter implements SaveSchedulePort {
    private final ScheduleJpaRepository scheduleJpaRepository;

    @Override
    public Schedule save(Schedule schedule) {
        // 도메인 -> Entity (순수 자바 객체를 JPA 전용 Entity로 변환)
        ScheduleJpaEntity saved = scheduleJpaRepository.save(ScheduleJpaEntity.from(schedule));
        // Entity -> 도메인 (순수 자바객체로 다시 변환)
        return saved.toDomain();
    }
}
