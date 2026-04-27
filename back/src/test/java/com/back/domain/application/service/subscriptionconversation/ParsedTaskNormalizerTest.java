package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.result.ParsedTask;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: ParsedTask normalizer")
class ParsedTaskNormalizerTest {

    @Test
    @DisplayName("normalizes real estate parser output into executable draft")
    void normalizesRealEstate() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(new DomainCapabilityRegistry());
        ParsedTask task = new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "",
                "0 9 * * *",
                "telegram",
                "api",
                "강남구 아파트 매매 실거래가 변동",
                List.of(),
                0.9,
                false,
                ""
        );

        SubscriptionDraft draft = normalizer.normalize(task, "강남구 아파트 매매 실거래가를 텔레그램으로 매일 아침 알려줘");

        assertThat(draft.domainName()).isEqualTo("real-estate");
        assertThat(draft.intent()).isEqualTo("apartment_trade_price");
        assertThat(draft.toolName()).isEqualTo("search_house_price");
        assertThat(draft.monitoringParams()).containsEntry("region", "강남구");
        assertThat(draft.cronExpr()).isEqualTo("0 0 9 * * *");
        assertThat(draft.notificationChannel()).isEqualTo("TELEGRAM_DM");
        assertThat(draft.missingFields()).isEmpty();
    }

    @Test
    @DisplayName("does not accept parser default channel unless user explicitly mentioned it")
    void ignoresImplicitDefaultChannel() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(new DomainCapabilityRegistry());
        ParsedTask task = new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "",
                "0 9 * * *",
                "discord",
                "api",
                "강남구 아파트 매매 실거래가 변동",
                List.of(),
                0.8,
                false,
                ""
        );

        SubscriptionDraft draft = normalizer.normalize(task, "강남구 아파트 매매 실거래가 알려줘");

        assertThat(draft.notificationChannel()).isNull();
        assertThat(draft.missingFields()).contains("notificationChannel");
    }

    @Test
    @DisplayName("does not accept parser default cadence unless user explicitly mentioned it")
    void ignoresImplicitDefaultCadence() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(new DomainCapabilityRegistry());
        ParsedTask task = new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "",
                "0 9 * * *",
                "telegram",
                "api",
                "강남구 아파트 매매 실거래가 변동",
                List.of(),
                0.8,
                false,
                ""
        );

        SubscriptionDraft draft = normalizer.normalize(task, "강남구 아파트 매매 실거래가 텔레그램으로 알려줘");

        assertThat(draft.cronExpr()).isNull();
        assertThat(draft.missingFields()).contains("cadence");
    }

    @Test
    @DisplayName("modify and delete intents are not handled by the creation flow")
    void nonCreateIntentIsNotExecutable() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(new DomainCapabilityRegistry());
        ParsedTask task = new ParsedTask(
                "delete",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "",
                "",
                "",
                "",
                "강남구 아파트 매매 실거래가 변동",
                List.of(),
                0.8,
                true,
                "어떤 알림을 삭제할까요?"
        );

        SubscriptionDraft draft = normalizer.normalize(task, "강남구 알림 삭제해줘");

        assertThat(draft.toolName()).isNull();
        assertThat(draft.missingFields()).contains("unsupportedIntent");
        assertThat(draft.assistantMessage()).contains("아직");
    }

    @Test
    @DisplayName("planned domains are classified but not executable")
    void plannedDomainIsNotExecutable() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(new DomainCapabilityRegistry());
        ParsedTask task = new ParsedTask(
                "create",
                "채용",
                "카카오 백엔드 채용공고",
                "경력 3년 이하",
                "0 * * * *",
                "email",
                "crawl",
                "카카오 백엔드 채용공고",
                List.of(),
                0.9,
                false,
                ""
        );

        SubscriptionDraft draft = normalizer.normalize(task, "카카오 채용공고 이메일로 알려줘");

        assertThat(draft.domainName()).isEqualTo("recruitment");
        assertThat(draft.toolName()).isNull();
        assertThat(draft.missingFields()).contains("unsupportedCapability");
        assertThat(draft.assistantMessage()).contains("준비 중");
    }
}
