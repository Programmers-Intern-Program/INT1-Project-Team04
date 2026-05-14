package com.back.domain.application.event;

import com.back.domain.application.service.SubscriptionContext;

/**
 * 구독 확정 직후 baseline 수집을 비동기로 실행하라는 신호.
 * {@code SubscriptionConversationService.confirm()} 트랜잭션 커밋 후 listener가
 * {@code runSubscriptionExecutionPort.execute()}를 가상 스레드로 호출한다.
 *
 * LLM/MCP 다단계 호출을 HTTP 응답 경로에서 분리하기 위한 도메인 이벤트.
 */
public record BaselineInitializationRequested(SubscriptionContext context) {
}
