package com.back.domain.application.port.out;

import com.back.domain.model.notification.Notification;

/**
 * [Outbound Port] Notification 생성 및 전송 내역을 저장하기 위한 인터페이스
 */
public interface SaveNotificationPort {
    Notification save(Notification notification);
}
