package com.back.domain.adapter.out.notification;

import com.back.domain.application.port.out.SendNotificationPort;
import com.back.domain.model.notification.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * [Infrastructure Adapter] 알림 발송 내용을 로그로 기록하는 더미(Dummy) 어댑터
 * * 실제 외부 알림 서비스와 연동하기 전에
 * * 비즈니스 로직에서 생성된 알림 메시지가 정상적으로 전달되는지 콘솔에서 확인하기 위해 사용
 */
@Slf4j
@Component
public class LoggingNotificationAdapter implements SendNotificationPort {

    @Override
    public boolean send(Notification notification) {
        log.info("notification channel={}, userId={}, message={}", notification.channel(), notification.user().id(), notification.message());
        return true;
    }
}
