package com.back.domain.application.port.out;

import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringBriefingResult;
import java.util.Optional;

public interface GenerateMonitoringBriefingPort {

    Optional<MonitoringBriefingResult> generate(MonitoringBriefingRequest request);
}
