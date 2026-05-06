package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: 구독 실행 프롬프트 테스트")
class PromptTemplateTest {

    @Test
    @DisplayName("구독 실행 프롬프트는 check_api_cache를 가장 먼저 호출하도록 강제한다")
    void subscriptionExecutionPromptRequiresCheckApiCacheFirst() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("check_api_cache")
                .contains("Step 1")
                .contains("다른 도구보다 먼저");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 첫 fetch(last_fetched_at=null) 시 알림 발송을 금지한다")
    void subscriptionExecutionPromptSkipsNotificationOnFirstFetch() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("last_fetched_at")
                .contains("null")
                .contains("send_notification을 호출하지 않는다");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 stale 캐시를 baseline으로 보관 후 새 데이터와 비교하는 흐름을 정의한다")
    void subscriptionExecutionPromptDefinesStaleCacheFlow() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("stale")
                .contains("cached_data")
                .contains("baseline")
                .containsSubsequence("cached_data", "baseline", "search_");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 도메인별 신선도 기준을 포함한다")
    void subscriptionExecutionPromptContainsFreshnessCriteriaByDomain() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("신선도 기준")
                .contains("법령")
                .contains("채용")
                .contains("경매")
                .contains("부동산");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 변화가 없을 때 알림 발송을 금지한다")
    void subscriptionExecutionPromptSkipsNotificationWhenNoChange() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("변화가 없는 경우 send_notification을 호출하지 않는다");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 MCP 알림 발송 도구 호출 계약을 포함한다")
    void subscriptionExecutionPromptRequiresSendNotificationContract() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("send_notification")
                .contains("notificationChannel")
                .contains("notificationTarget")
                .contains("자연어 응답")
                .contains("structured.sent")
                .contains("true");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 알림 본문 작성 원칙(이전값→현재값 형식, 한국어)을 포함한다")
    void subscriptionExecutionPromptDefinesNotificationBodyPrinciples() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("이전값")
                .contains("현재값")
                .contains("한국어");
    }
}

