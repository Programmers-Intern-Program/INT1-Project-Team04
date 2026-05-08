package com.back.domain.application.port.out;

public interface RunAiMonitorPort {
    // 구독 조회·분석·Discord 발송은 MCP 서버 내부에서 처리. Spring Boot는 트리거만 전달.
    void run();
}
