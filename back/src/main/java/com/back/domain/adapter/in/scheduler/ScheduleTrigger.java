package com.back.domain.adapter.in.scheduler;

import com.back.domain.application.port.in.RunSubscriptionMonitorUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * [Incoming Adapter] 시스템 내부의 비즈니스 로직을 주기적으로 실행시키는 스케줄러 trigger
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleTrigger {

    private final RunSubscriptionMonitorUseCase runSubscriptionMonitorUseCase;

    @Scheduled(fixedDelayString = "${schedule.monitor.fixed-delay-ms:300000}")
    public void runMonitor() {
        try {
            runSubscriptionMonitorUseCase.runAll();
        } catch (Exception e) {
            log.error("[ScheduleTrigger] 구독 모니터 실행 실패", e);
        }
    }
}
