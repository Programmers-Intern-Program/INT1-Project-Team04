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
    @DisplayName("구독 실행 프롬프트는 최종 실행 증빙 JSON 응답 계약을 포함한다")
    void subscriptionExecutionPromptRequiresFinalExecutionEvidenceJson() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("최종 응답")
                .contains("JSON만 반환")
                .contains("subscriptionId")
                .contains("dataToolExecuted")
                .contains("compareExecuted")
                .contains("notificationRequired")
                .contains("notificationSent");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 캐시 가능한 모든 데이터 도구에서 최신 캐시를 우선 사용하게 한다")
    void subscriptionExecutionPromptUsesFreshCacheBeforeFetchForCacheableTools() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("모든 캐시 가능한 데이터 도구")
                .contains("check_api_cache")
                .contains("get_cached_data")
                .contains("신선한 cache_hit")
                .contains("외부 데이터 도구를 호출하지 마세요")
                .contains("search_house_price")
                .contains("search_apt_rent")
                .contains("search_offi_trade")
                .contains("search_offi_rent")
                .contains("search_rh_trade")
                .contains("search_rh_rent")
                .contains("search_law_info")
                .contains("search_bill_info")
                .contains("search_g2b_bid")
                .contains("search_public_job")
                .contains("search_worknet_job");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 MCP가 AI 분석 필요로 표시한 경우에만 브리핑을 생성한다")
    void subscriptionExecutionPromptUsesAiAnalysisGateFromMcp() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("structured.requires_ai_analysis=false")
                .contains("AI 분석과 AI 브리핑을 생성하지 마세요")
                .contains("structured.requires_ai_analysis=true")
                .contains("structured.condition_satisfied=true")
                .contains("AI 브리핑");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 채용 신규 공고 제목과 링크를 출처별로 브리핑하게 한다")
    void subscriptionExecutionPromptSeparatesRecruitmentBriefingPostingsBySource() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("current.sources")
                .contains("briefing_postings_by_source")
                .contains("public_job")
                .contains("worknet_job")
                .contains("공공채용")
                .contains("워크넷")
                .contains("제목")
                .contains("링크")
                .contains("권한 거부");
    }

    @Test
    @DisplayName("구독 실행 프롬프트는 부동산 가격변동 브리핑에 비교 수치와 데이터 범위를 포함하게 한다")
    void subscriptionExecutionPromptRequiresRealEstateBriefingDetails() {
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("부동산 가격변동")
                .contains("기준값")
                .contains("현재값")
                .contains("변화율")
                .contains("거래연월")
                .contains("거래건수")
                .contains("데이터 출처")
                .contains("API 캐시")
                .contains("한 줄로 끝내지 마세요");
        assertThat(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                .contains("내부 처리 경로는 알림 본문에 쓰지 마세요");
    }
}
