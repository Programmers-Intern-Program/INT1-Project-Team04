package com.back.domain.application.service.subscriptionconversation;

import java.util.List;
import java.util.Map;

/**
 * [Application Result] MCP 서버가 정규화한 구독 도메인 초안
 * * domainName / intent / toolName / monitoringParams 는 실제 MCP 도구 실행 계약에 가까운 값이다.
 * * cronExpr, notificationChannel, notificationTargetAddress 같은 사용자별 대화/전달 설정은
 * 백엔드 ParsedTaskNormalizer가 별도로 합성한다.
 * * missingFields 는 저장 가능 여부를 막는 최종 방어선으로 사용되므로 null 대신 빈 컬렉션으로 보정한다.
 */
public record DomainNormalizedSubscriptionDraft(
        String query,
        String domainName,
        String intent,
        String toolName,
        Map<String, String> monitoringParams,
        List<String> missingFields,
        String assistantMessage,
        double confidence
) {
    public DomainNormalizedSubscriptionDraft {
        monitoringParams = monitoringParams == null ? Map.of() : Map.copyOf(monitoringParams);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }
}
