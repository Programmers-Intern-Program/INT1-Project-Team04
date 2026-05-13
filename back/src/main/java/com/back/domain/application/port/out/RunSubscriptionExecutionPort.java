package com.back.domain.application.port.out;

import com.back.domain.application.service.SubscriptionContext;

/**
 * [Outgoing Port] 구독 실행 요청 포트.
 * Spring AI를 통해 MCP server에 구독 실행을 위임한다.
 * 검색(RunAiMonitorPort)과 구독 실행은 별개 흐름이므로 분리.
 */
public interface RunSubscriptionExecutionPort {
    void execute(SubscriptionContext subscription);
}
