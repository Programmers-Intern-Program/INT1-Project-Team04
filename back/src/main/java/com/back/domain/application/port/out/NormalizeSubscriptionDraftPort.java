package com.back.domain.application.port.out;

import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.service.subscriptionconversation.DomainNormalizedSubscriptionDraft;
import com.back.domain.application.service.subscriptionconversation.SubscriptionDraft;
import java.util.Optional;

/**
 * [Outgoing Port] 구독 초안의 도메인별 구조화를 MCP 서버에 위임하기 위한 포트
 * * 백엔드 AI 파서는 자연어를 ParsedTask 수준으로 초벌 파싱하고,
 * MCP 서버는 실제 실행 가능한 도구 계약(region, condition, toolName, missingFields 등)으로 정규화한다.
 * * MCP 호출 실패나 응답 파싱 실패는 Optional.empty() 또는 ApiException 으로 드러내며,
 * 백엔드는 더 이상 로컬 도메인 파싱 fallback 으로 구독 초안을 만들지 않는다.
 */
public interface NormalizeSubscriptionDraftPort {
    /**
     * AI 파서 결과와 사용자 원문, 이전 대화 초안을 MCP 서버의 normalize_subscription_draft 입력으로 전달한다.
     *
     * @param task AI 파서가 만든 단일 작업 초안
     * @param userMessage 현재 턴 사용자 원문. parser 기본값과 사용자 명시값을 구분하는 기준으로 사용된다.
     * @param previousDraft 멀티 턴 대화에서 재사용 가능한 이전 초안. 신규 대화면 null.
     * @return MCP가 도메인 실행 계약으로 정규화한 초안. 비어 있으면 호출자는 실패로 처리한다.
     */
    Optional<DomainNormalizedSubscriptionDraft> normalize(
            ParsedTask task,
            String userMessage,
            SubscriptionDraft previousDraft
    );
}
