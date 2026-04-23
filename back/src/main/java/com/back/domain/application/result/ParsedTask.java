package com.back.domain.application.result;

import java.util.List;

/**
 * [Application Result] 단일 파싱 태스크 결과
 */
public record ParsedTask(
        String intent,
        String domainName,
        String query,
        String condition,
        String cronExpr,
        String channel,
        String apiType,
        String target,
        List<String> urls,
        double confidence,
        boolean needsConfirmation,
        String confirmationQuestion
) {
}