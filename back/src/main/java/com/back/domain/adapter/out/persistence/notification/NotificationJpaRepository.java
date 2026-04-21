package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.model.notification.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * [Persistence Adapter의 도구] Notification 엔티티에 대한 실제 DB 접근을 담당하는 JPA 레포지토리
 */
public interface NotificationJpaRepository extends JpaRepository<NotificationJpaEntity, String> {
    List<NotificationJpaEntity> findByStatus(NotificationStatus status);
}
