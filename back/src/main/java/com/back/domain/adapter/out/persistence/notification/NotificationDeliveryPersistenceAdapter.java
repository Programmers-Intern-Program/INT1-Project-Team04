package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.application.port.out.LoadDispatchableNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryPersistenceAdapter implements SaveNotificationDeliveryPort, LoadDispatchableNotificationDeliveryPort {

    private final NotificationDeliveryJpaRepository repository;

    @Override
    @Transactional
    public NotificationDelivery save(NotificationDelivery delivery) {
        return repository.save(NotificationDeliveryJpaEntity.from(delivery)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDelivery> loadDispatchable(LocalDateTime now) {
        return repository.findDispatchable(
                        now,
                        NotificationDeliveryStatus.PENDING,
                        NotificationDeliveryStatus.RETRY
                )
                .stream()
                .map(NotificationDeliveryJpaEntity::toDomain)
                .toList();
    }
}
