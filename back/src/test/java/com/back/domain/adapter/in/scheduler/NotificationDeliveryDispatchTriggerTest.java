package com.back.domain.adapter.in.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.back.domain.application.service.NotificationDispatcherService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Scheduler: 알림 발송 트리거 테스트")
class NotificationDeliveryDispatchTriggerTest {

    @Test
    @DisplayName("Scheduler: 발송 대기 알림을 dispatcher에 위임한다")
    void dispatchesPendingNotificationDeliveries() {
        NotificationDispatcherService dispatcherService = mock(NotificationDispatcherService.class);
        NotificationDeliveryDispatchTrigger trigger = new NotificationDeliveryDispatchTrigger(dispatcherService);

        trigger.run();

        verify(dispatcherService).dispatchPending(any(LocalDateTime.class));
    }
}
