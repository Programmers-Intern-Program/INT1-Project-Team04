package com.back.domain.adapter.in.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.back.domain.application.port.in.RunSubscriptionMonitorUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Scheduler: 구독 모니터링 트리거 테스트")
class ScheduleTriggerTest {

    @Test
    @DisplayName("Scheduler: Spring AI 구독 모니터링 흐름만 실행한다")
    void runsOnlySubscriptionMonitorFlow() {
        RunSubscriptionMonitorUseCase monitorUseCase = mock(RunSubscriptionMonitorUseCase.class);
        ScheduleTrigger trigger = new ScheduleTrigger(monitorUseCase);

        trigger.runMonitor();

        verify(monitorUseCase).runAll();
    }
}
