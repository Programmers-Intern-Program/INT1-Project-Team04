package com.back.domain.application.port.out;

import com.back.domain.application.result.PromoteTokenConsumption;

public interface ConsumeBaselinePromoteTokenPort {

    /**
     * 토큰을 검증하고 (dryRun=false 이면) 사용 마킹한다.
     * dryRun=true 인 경우 토큰을 소비하지 않고 사용 가능 여부만 확인한다 (메일 prefetch 대비).
     */
    PromoteTokenConsumption consumeOrPeek(String token, boolean dryRun);
}
