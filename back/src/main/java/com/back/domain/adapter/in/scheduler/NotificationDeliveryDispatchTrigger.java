package com.back.domain.adapter.in.scheduler;

import com.back.domain.application.service.NotificationDispatcherService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryDispatchTrigger {

    private final NotificationDispatcherService notificationDispatcherService;

    @Scheduled(fixedDelayString = "${notification.dispatcher.fixed-delay-ms:5000}")
    public void run() {
        try {
            notificationDispatcherService.dispatchPending(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[NotificationDeliveryDispatchTrigger] 알림 발송 디스패치 실패", e);
        }
    }
}
