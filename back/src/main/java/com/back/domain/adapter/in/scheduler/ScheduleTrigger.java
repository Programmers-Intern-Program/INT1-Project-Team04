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

    //TODO: 쓰지 않는거면 관려된 것 지울 것.
    @Scheduled(fixedDelayString = "${schedule.runner.fixed-delay-ms:60000}")
    public void run() {
        runDueSchedulesUseCase.runDueSchedules();
    }

    @Scheduled(fixedDelayString = "${schedule.monitor.fixed-delay-ms:300000}")
    public void runMonitor() {
        runSubscriptionMonitorUseCase.runAll();
    }
}
