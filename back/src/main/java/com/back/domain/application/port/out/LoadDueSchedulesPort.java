package com.back.domain.application.port.out;

import com.back.domain.model.schedule.Schedule;
import java.time.LocalDateTime;
import java.util.List;

public interface LoadDueSchedulesPort {

    List<Schedule> loadDueSchedules(LocalDateTime now);
}
