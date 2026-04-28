package com.back.domain.application.port.in;

public interface CancelSubscriptionUseCase {
    void cancelForUser(Long userId, String subscriptionId);
}
