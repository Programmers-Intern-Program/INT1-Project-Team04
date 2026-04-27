package com.back.domain.adapter.out.persistence.subscription;

import com.back.domain.adapter.out.persistence.notification.NotificationPreferenceJpaRepository;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaRepository;
import com.back.domain.application.port.out.LoadDuplicateSubscriptionPort;
import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Persistence Adapter] subscription 정보를 DB에 저장하는 어댑터
 * * 비즈니스 규칙이 담긴 'Subscription' 도메인 모델을 받아
 * * JPA를 통해 DB에 반영하고 저장된 결과를 다시 도메인 모델로 변환하여 반환합니다.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionPersistenceAdapter implements SaveSubscriptionPort, LoadDuplicateSubscriptionPort {

    private final SubscriptionJpaRepository subscriptionJpaRepository;
    private final ScheduleJpaRepository scheduleJpaRepository;
    private final NotificationPreferenceJpaRepository notificationPreferenceJpaRepository;

    @Override
    @Transactional
    public Subscription save(Subscription subscription) {
        SubscriptionJpaEntity saved = subscriptionJpaRepository.save(SubscriptionJpaEntity.from(subscription));

        return new Subscription(
            saved.getId(),
            new User(
                    saved.getUser().getId(),
                    saved.getUser().getEmail(),
                    saved.getUser().getNickname(),
                    saved.getUser().getCreatedAt(),
                    saved.getUser().getDeletedAt()),
            new Domain(saved.getDomain().getId(), saved.getDomain().getName()),
            saved.getQuery(),
            saved.getIntent(),
            saved.isActive(),
            saved.getCreatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveDuplicate(
            Long userId,
            Long domainId,
            String normalizedQuery,
            String cronExpr,
            NotificationChannel notificationChannel
    ) {
        return subscriptionJpaRepository.findByUserIdAndDomainIdAndActiveTrue(userId, domainId)
                .stream()
                .filter(subscription -> normalizeQuery(subscription.getQuery()).equals(normalizedQuery))
                .anyMatch(subscription ->
                        hasSameCron(subscription.getId(), cronExpr)
                                && hasSameNotificationChannel(subscription.getId(), notificationChannel)
                );
    }

    private boolean hasSameCron(String subscriptionId, String cronExpr) {
        return scheduleJpaRepository.findFirstBySubscriptionIdOrderByNextRunAsc(subscriptionId)
                .map(schedule -> schedule.getCronExpr().equals(cronExpr))
                .orElse(false);
    }

    private boolean hasSameNotificationChannel(String subscriptionId, NotificationChannel notificationChannel) {
        return notificationPreferenceJpaRepository.findBySubscriptionIdAndEnabledTrue(subscriptionId)
                .stream()
                .findFirst()
                .map(preference -> preference.getChannel() == notificationChannel)
                .orElse(notificationChannel == null);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
