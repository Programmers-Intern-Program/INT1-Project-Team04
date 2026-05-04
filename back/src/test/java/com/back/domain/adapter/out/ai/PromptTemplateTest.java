package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: 구독 실행 프롬프트 테스트")
class PromptTemplateTest {

    @Test
    @DisplayName("파서 프롬프트는 채용 새 공고 조건을 1건 이상 증가 조건으로 해석하게 한다")
    void parserPromptNormalizesRecruitmentNewPostingCondition() {
        assertThat(PromptTemplate.SYSTEM_PROMPT)
                .contains("채용 도메인")
                .contains("새 공고")
                .contains("1건 이상 증가")
                .contains("needs_confirmation을 false")
                .contains("진행중 공고")
                .contains("진행중 공고 1건 이상 증가");
    }

    @Test
    @DisplayName("파서 프롬프트는 채용 query와 target에 시간/채널 문구를 섞지 않게 한다")
    void parserPromptKeepsRecruitmentQueryFocusedOnSearchTarget() {
        assertThat(PromptTemplate.SYSTEM_PROMPT)
                .contains("채용 query")
                .contains("검색 키워드")
                .contains("시간")
                .contains("채널")
                .contains("섞지 마");
    }

    @Test
    @DisplayName("후속 파서 프롬프트는 채용 조건 답변도 1건 이상 증가 조건으로 보정한다")
    void continuePromptNormalizesRecruitmentConditionAnswers() {
        assertThat(PromptTemplate.CONTINUE_SYSTEM_PROMPT)
                .contains("채용")
                .contains("새 공고")
                .contains("1건 이상 증가")
                .contains("needs_confirmation을 false");
    }

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

    @Test
    @DisplayName("구독 실행 프롬프트는 변화 비교 도구와 브리핑 근거 필드 계약을 포함한다")
    void subscriptionExecutionPromptRequiresCompareSubscriptionChangeContract() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("compare_subscription_change")
                .contains("briefing_facts")
                .contains("diffs")
                .contains("baseline_initialized")
                .contains("structured.changed");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 베이스라인 초기화와 미변경 시 알림을 금지한다")
    void subscriptionExecutionPromptSkipsNotificationWhenBaselineInitializedOrUnchanged() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("structured.baseline_initialized=true")
                .contains("알림을 보내지 마")
                .contains("structured.changed=false")
                .contains("알림을 보내지 마");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 데이터 도구 이후 변화 비교 도구를 호출한 뒤 조건 충족 시 알림을 보낸다")
    void subscriptionExecutionPromptDefinesDataCompareNotificationOrder() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .containsSubsequence("데이터 도구", "compare_subscription_change", "send_notification")
                .contains("condition")
                .contains("structured.diffs")
                .contains("structured.briefing_facts")
                .contains("AI 브리핑");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 MCP가 AI 분석 필요로 표시한 경우에만 브리핑을 생성한다")
    void subscriptionExecutionPromptUsesAiAnalysisGateFromMcp() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("structured.requires_ai_analysis=false")
                .contains("AI 분석과 AI 브리핑을 생성하지 마세요")
                .contains("structured.requires_ai_analysis=true")
                .contains("AI 브리핑");
    }
}
