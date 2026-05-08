package com.back.domain.application.result;

import java.util.List;

/**
 * [Application Result] 전체 파싱 결과 (세션 ID + 태스크 리스트)
 */
public record ParseResult(
        String sessionId,
        List<ParsedTask> tasks
) {
}
