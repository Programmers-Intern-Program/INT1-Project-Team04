package com.back.domain.application.port.out;

import com.back.domain.model.schedule.Schedule;
import java.util.Optional;

public interface LoadSubscriptionSchedulePort {
    Optional<Schedule> loadFirstBySubscriptionId(String subscriptionId);
}
