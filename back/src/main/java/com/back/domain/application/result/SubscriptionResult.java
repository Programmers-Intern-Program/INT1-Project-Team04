package com.back.domain.application.result;

import java.time.LocalDateTime;

/**
 * [Application Result] 구독 생성 결과를 전달하는 응답 모델
 */
public record SubscriptionResult(
        String id,
        Long userId,
        Long domainId,
        String query,
        boolean active,
        LocalDateTime createdAt,
        String scheduleId,
        String cronExpr,
        LocalDateTime nextRun
) {
}
