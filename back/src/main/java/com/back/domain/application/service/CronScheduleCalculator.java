package com.back.domain.application.service;

import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import org.springframework.scheduling.support.CronExpression;

/**
 * [Application Helper] cron 표현식을 기준으로 다음 실행 시각을 계산하는 유틸리티
 */
final class CronScheduleCalculator {

    private CronScheduleCalculator() {
    }

    static LocalDateTime nextRun(String cronExpr, LocalDateTime from) {
        try {
            LocalDateTime nextRun = CronExpression.parse(cronExpr).next(from);
            if (nextRun == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }
            return nextRun;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }
}
