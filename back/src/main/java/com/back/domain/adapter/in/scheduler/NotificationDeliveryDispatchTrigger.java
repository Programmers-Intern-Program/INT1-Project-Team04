package com.back.domain.adapter.in.scheduler;

import com.back.domain.application.service.NotificationDispatcherService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryDispatchTrigger {

    private final NotificationDispatcherService notificationDispatcherService;

    @Scheduled(fixedDelayString = "${notification.dispatcher.fixed-delay-ms:5000}")
    public void run() {
        notificationDispatcherService.dispatchPending(LocalDateTime.now());
    }
}
