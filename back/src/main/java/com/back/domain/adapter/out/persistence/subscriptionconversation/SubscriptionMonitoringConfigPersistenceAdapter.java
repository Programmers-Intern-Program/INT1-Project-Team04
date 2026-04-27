package com.back.domain.adapter.out.persistence.subscriptionconversation;

import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SubscriptionMonitoringConfigPersistenceAdapter implements LoadSubscriptionMonitoringConfigPort {

    private final SubscriptionMonitoringConfigJpaRepository repository;

    @Override
    public Optional<SubscriptionMonitoringConfig> loadBySubscriptionId(String subscriptionId) {
        return repository.findBySubscriptionId(subscriptionId)
                .map(SubscriptionMonitoringConfigJpaEntity::toDomain);
    }
}
