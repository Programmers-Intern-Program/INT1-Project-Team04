package com.back.domain.application.port.out;

import com.back.domain.model.subscription.Subscription;
import java.util.Optional;

public interface LoadSubscriptionPort {
    Optional<Subscription> loadActiveByIdAndUserId(String subscriptionId, Long userId);
}
