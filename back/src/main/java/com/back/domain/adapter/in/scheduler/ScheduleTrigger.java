package com.back.domain.adapter.in.scheduler;

import com.back.domain.application.port.in.RunDueSchedulesUseCase;
import com.back.domain.application.port.in.RunSubscriptionMonitorUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * [Incoming Adapter] 시스템 내부의 비즈니스 로직을 주기적으로 실행시키는 스케줄러 trigger
 */
@Component
@RequiredArgsConstructor
public class ScheduleTrigger {

    private final RunDueSchedulesUseCase runDueSchedulesUseCase;
    private final RunSubscriptionMonitorUseCase runSubscriptionMonitorUseCase;

    // @Deprecated: ScheduleExecutionService(직접 MCP 호출)를 사용하던 구 흐름.
    // SubscriptionMonitorService(Spring AI → MCP server 위임)로 교체됨.
    // runDueSchedulesUseCase 및 ScheduleExecutionService는 교체 안정화 후 제거 예정.
    @Deprecated
    public void run() {
        runDueSchedulesUseCase.runDueSchedules();
    }

    @Scheduled(fixedDelayString = "${schedule.monitor.fixed-delay-ms:300000}")
    public void runMonitor() {
        runSubscriptionMonitorUseCase.runAll();
    }
}
