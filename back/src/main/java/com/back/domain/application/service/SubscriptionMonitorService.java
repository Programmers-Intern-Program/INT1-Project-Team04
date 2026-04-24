package com.back.domain.application.service;

import com.back.domain.application.port.in.RunSubscriptionMonitorUseCase;
import com.back.domain.application.port.out.RunAiMonitorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * [Domain Service] 구독 모니터링 트리거. 실제 구독 조회·분석은 MCP 서버 담당.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionMonitorService implements RunSubscriptionMonitorUseCase {

    private final RunAiMonitorPort runAiMonitorPort;

    @Override
    public void runAll() {
        log.info("[SubscriptionMonitorService] 구독 모니터링 시작");
        runAiMonitorPort.run();
    }
}
