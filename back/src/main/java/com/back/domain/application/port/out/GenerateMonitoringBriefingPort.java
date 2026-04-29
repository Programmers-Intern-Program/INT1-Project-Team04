package com.back.domain.application.port.out;

import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import java.util.Optional;

public interface GenerateMonitoringBriefingPort {

    Optional<String> generate(MonitoringBriefingRequest request);
}
