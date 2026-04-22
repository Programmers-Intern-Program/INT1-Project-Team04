package com.back.domain.adapter.in.web.subscription;

import com.back.domain.application.result.SubscriptionResult;
import java.time.LocalDateTime;

/**
 * [Incoming Web Adapter DTO] 구독 생성 결과를 클라이언트에 전달하는 응답
 */
public record SubscriptionResponse(
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

    public static SubscriptionResponse from(SubscriptionResult result) {
        return new SubscriptionResponse(
                result.id(),
                result.userId(),
                result.domainId(),
                result.query(),
                result.active(),
                result.createdAt(),
                result.scheduleId(),
                result.cronExpr(),
                result.nextRun()
        );
    }
}
