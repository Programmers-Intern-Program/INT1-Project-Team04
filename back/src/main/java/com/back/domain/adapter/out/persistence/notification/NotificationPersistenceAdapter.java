package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.application.port.out.SaveNotificationPort;
import com.back.domain.model.notification.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [Persistence Adapter] Notification 데이터를 DB에 저장하는 어댑터
 */
@Component
@RequiredArgsConstructor
public class NotificationPersistenceAdapter implements SaveNotificationPort {
    private final NotificationJpaRepository notificationJpaRepository;

    public Notification save(Notification notification) {
        NotificationJpaEntity saved = notificationJpaRepository.save(NotificationJpaEntity.from(notification));
        return saved.toDomain();
    }
}
