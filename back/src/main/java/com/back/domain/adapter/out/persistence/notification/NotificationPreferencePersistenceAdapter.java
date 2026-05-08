package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.SaveNotificationPreferencePort;
import com.back.domain.model.notification.NotificationPreference;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationPreferencePersistenceAdapter implements LoadEnabledNotificationPreferencePort, SaveNotificationPreferencePort {

    private final NotificationPreferenceJpaRepository repository;

    @Override
    @Transactional
    public NotificationPreference save(NotificationPreference preference) {
        if (preference.enabled()) {
            repository.findBySubscriptionIdAndEnabledTrue(preference.subscriptionId()).stream()
                    .filter(existing -> !existing.getId().equals(preference.id()))
                    .forEach(NotificationPreferenceJpaEntity::disable);
        }

        return repository.save(NotificationPreferenceJpaEntity.from(preference)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreference> loadEnabledBySubscriptionId(String subscriptionId) {
        return repository.findBySubscriptionIdAndEnabledTrue(subscriptionId).stream()
                .map(NotificationPreferenceJpaEntity::toDomain)
                .toList();
    }
}
