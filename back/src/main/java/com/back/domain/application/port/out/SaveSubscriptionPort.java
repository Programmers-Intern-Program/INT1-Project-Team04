package com.back.domain.application.port.out;

import com.back.domain.model.subscription.Subscription;

/**
 * [Outbound Port] 구독 정보를 저장하기 위한 인터페이스
 */
public interface SaveSubscriptionPort {
    Subscription save(Subscription subscription);
}
