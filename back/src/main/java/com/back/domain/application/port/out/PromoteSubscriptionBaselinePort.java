package com.back.domain.application.port.out;

import com.back.domain.application.result.PromoteBaselineMcpResult;

public interface PromoteSubscriptionBaselinePort {

    /**
     * MCP `promote_subscription_baseline` Tool 을 호출해 baseline 을 latest 로 승격시킨다.
     *
     * @param subscriptionId 메인 DB subscription.id
     * @param paramsHash     MCP DB 행 식별자. null 이면 모든 행 갱신.
     */
    PromoteBaselineMcpResult promote(String subscriptionId, String paramsHash);
}
