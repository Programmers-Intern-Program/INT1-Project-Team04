package com.back.domain.adapter.out.ai;

import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;

public final class MonitoringBriefingPromptTemplate {

    private static final int MAX_CONTENT_LENGTH = 4_000;

    public static final String SYSTEM_PROMPT = """
            당신은 구독 변화 감지 결과를 사용자에게 보내는 브리핑 알림 작성자입니다.
            입력으로 제공된 지표명, 이전 값, 현재 값, 변화량, 변화율은 절대 바꾸지 마세요.
            사용자가 바로 읽을 수 있게 짧고 구체적인 한국어로 작성하세요.
            반드시 아래 JSON 객체 하나만 반환하세요. 마크다운 코드 블록은 사용하지 마세요.

            {
              "title": "한 줄 제목",
              "summary": "핵심 변화 요약 1~2문장",
              "keyChanges": ["숫자 기반 핵심 변화"],
              "watchPoints": ["사용자가 다음에 확인할 점"]
            }
            """;

    private MonitoringBriefingPromptTemplate() {
    }

    public static String userPrompt(MonitoringBriefingRequest request) {
        return """
                [구독 질의]
                %s

                [MCP 도구]
                %s

                [변화 감지 결과]
                - 지표: %s
                - 이전 값: %s
                - 현재 값: %s
                - 변화량: %s
                - 변화율: %s%%

                [이전 summary JSON]
                %s

                [현재 summary JSON]
                %s

                [MCP 원문 요약/본문]
                %s
                """.formatted(
                value(request.subscriptionQuery()),
                value(request.toolName()),
                value(request.decision().metricKey()),
                request.decision().previousValue(),
                request.decision().currentValue(),
                request.decision().changeValue(),
                request.decision().changeRate(),
                value(request.previousSummaryJson()),
                value(request.currentSummaryJson()),
                truncate(request.mcpContent())
        );
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "(없음)" : value;
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "(없음)";
        }
        if (value.length() <= MAX_CONTENT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_CONTENT_LENGTH) + "\n...(truncated)";
    }
}
