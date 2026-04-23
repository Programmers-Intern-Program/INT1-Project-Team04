package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationDeliveryStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryJpaRepository extends JpaRepository<NotificationDeliveryJpaEntity, String> {

    @Query("""
            select delivery
            from NotificationDeliveryJpaEntity delivery
            where delivery.status = :pending
               or (
                    delivery.status = :retry
                    and (delivery.nextRetryAt is null or delivery.nextRetryAt <= :now)
               )
            order by delivery.createdAt asc, delivery.id asc
            """)
    List<NotificationDeliveryJpaEntity> findDispatchable(
            @Param("now") LocalDateTime now,
            @Param("pending") NotificationDeliveryStatus pending,
            @Param("retry") NotificationDeliveryStatus retry
    );
}
