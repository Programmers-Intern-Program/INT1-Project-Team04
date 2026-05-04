package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: 구독 실행 프롬프트 테스트")
class PromptTemplateTest {

    @Test
    @DisplayName("구독 실행 프롬프트는 MCP 알림 발송 도구 호출 계약을 포함한다")
    void subscriptionExecutionPromptRequiresSendNotificationTool() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("send_notification")
                .contains("notificationChannel")
                .contains("notificationTarget")
                .contains("자연어 응답")
                .contains("structured.sent")
                .contains("true");
    }
}
