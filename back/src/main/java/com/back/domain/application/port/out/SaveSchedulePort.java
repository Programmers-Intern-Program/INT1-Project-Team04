package com.back.domain.application.port.out;

import com.back.domain.model.schedule.Schedule;

/**
 * [Outbound Port] Schedule 정보를 DB에 저장하기 위한 인터페이스
 */
public interface SaveSchedulePort {
    Schedule save(Schedule schedule);
}
