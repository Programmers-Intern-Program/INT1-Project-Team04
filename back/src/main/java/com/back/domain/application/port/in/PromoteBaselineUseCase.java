package com.back.domain.application.port.in;

import com.back.domain.application.result.PromoteBaselineResult;

public interface PromoteBaselineUseCase {

    /**
     * baseline 갱신 1회용 토큰을 검증하고, MCP `promote_subscription_baseline` 을 호출한다.
     *
     * @param token  알림 메시지의 GET 링크에 담긴 토큰
     * @param dryRun true 면 토큰을 소비하지 않고 검증만 한다 (메일 prefetch 대비)
     */
    PromoteBaselineResult promote(String token, boolean dryRun);
}
