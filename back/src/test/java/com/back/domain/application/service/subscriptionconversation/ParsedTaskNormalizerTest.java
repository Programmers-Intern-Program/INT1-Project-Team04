package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.port.out.NormalizeSubscriptionDraftPort;
import com.back.domain.application.result.ParsedTask;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: ParsedTask normalizer")
class ParsedTaskNormalizerTest {

    @Test
    @DisplayName("does not fallback to backend domain parsing when MCP normalization is unavailable")
    void doesNotFallbackWhenMcpNormalizationIsUnavailable() {
        NormalizeSubscriptionDraftPort mcpNormalizer = (task, userMessage, previousDraft) -> Optional.empty();
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(mcpNormalizer);

        assertThatThrownBy(() -> normalizer.normalize(
                realEstateTask("강남구 아파트 매매 실거래가", "5% 이상 상승"),
                "강남구 아파트 매매 실거래가를 텔레그램으로 매일 알려줘"
        ))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MCP_REQUEST_FAILED);
    }

    @Test
    @DisplayName("merges MCP domain draft with internal check schedule and explicitly mentioned channel")
    void mergesMcpDomainDraftWithExplicitConversationFields() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(returning(domainDraft(
                "강남구 아파트 매매 실거래가",
                "search_house_price",
                Map.of(
                        "region", "강남구",
                        "conditionMetric", "AVG_PRICE",
                        "conditionDirection", "UP",
                        "conditionOperator", "GTE",
                        "conditionThreshold", "5",
                        "conditionUnit", "PERCENT"
                ),
                List.of(),
                ""
        )));

        SubscriptionDraft draft = normalizer.normalize(
                realEstateTask("강남구 아파트 매매 실거래가", "5% 이상 상승"),
                "강남구 아파트 매매 실거래가를 텔레그램으로 매일 오전 9시에 알려줘"
        );

        assertThat(draft.domainName()).isEqualTo("real-estate");
        assertThat(draft.intent()).isEqualTo("apartment_trade_price");
        assertThat(draft.toolName()).isEqualTo("search_house_price");
        assertThat(draft.monitoringParams()).containsEntry("region", "강남구");
        assertThat(draft.monitoringParams()).containsEntry("conditionThreshold", "5");
        assertThat(draft.cronExpr()).isEqualTo("0 0 * * * *");
        assertThat(draft.notificationChannel()).isEqualTo("TELEGRAM_DM");
        assertThat(draft.missingFields()).isEmpty();
    }

    @Test
    @DisplayName("keeps MCP missing fields and does not accept parser default channel")
    void ignoresImplicitDefaultChannel() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(returning(domainDraft(
                "강남구 아파트 매매 실거래가",
                "search_house_price",
                Map.of("region", "강남구"),
                List.of("condition"),
                "어떤 가격 변동 조건 시 알림을 받으시겠어요?"
        )));
        ParsedTask task = realEstateTask("강남구 아파트 매매 실거래가", "");

        SubscriptionDraft draft = normalizer.normalize(task, "강남구 아파트 매매 실거래가 알려줘");

        assertThat(draft.notificationChannel()).isNull();
        assertThat(draft.cronExpr()).isEqualTo("0 0 * * * *");
        assertThat(draft.missingFields()).containsExactly("condition", "notificationChannel");
        assertThat(draft.assistantMessage()).isEqualTo("어떤 가격 변동 조건 시 알림을 받으시겠어요?");
    }

    @Test
    @DisplayName("uses internal check schedule instead of asking for cadence")
    void usesInternalCheckScheduleInsteadOfAskingForCadence() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(returning(domainDraft(
                "강남구 아파트 매매 실거래가",
                "search_house_price",
                Map.of("region", "강남구"),
                List.of("condition"),
                ""
        )));

        SubscriptionDraft draft = normalizer.normalize(
                realEstateTask("강남구 아파트 매매 실거래가", ""),
                "강남구 아파트 매매 실거래가 텔레그램으로 알려줘"
        );

        assertThat(draft.cronExpr()).isEqualTo("0 0 * * * *");
        assertThat(draft.notificationChannel()).isEqualTo("TELEGRAM_DM");
        assertThat(draft.missingFields()).containsExactly("condition");
        assertThat(draft.assistantMessage()).contains("가격 변동 조건");
    }

    @Test
    @DisplayName("reuses previous channel when MCP keeps the same domain")
    void reusesPreviousConversationFieldsForSameDomain() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(returning(domainDraft(
                "강남구 아파트 매매 실거래가",
                "search_house_price",
                Map.of(
                        "region", "강남구",
                        "conditionMetric", "AVG_PRICE",
                        "conditionDirection", "UP",
                        "conditionOperator", "GTE",
                        "conditionThreshold", "5",
                        "conditionUnit", "PERCENT"
                ),
                List.of(),
                ""
        )));
        SubscriptionDraft previousDraft = new SubscriptionDraft(
                "강남구 아파트 매매 실거래가",
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                Map.of("region", "강남구"),
                "0 0 9 * * *",
                "TELEGRAM_DM",
                "123456789",
                List.of(),
                "",
                0.8
        );

        SubscriptionDraft draft = normalizer.normalize(
                realEstateTask("강남구 아파트 매매 실거래가", "5% 이상 상승"),
                "5% 이상 상승하면 알려줘",
                previousDraft
        );

        assertThat(draft.cronExpr()).isEqualTo("0 0 * * * *");
        assertThat(draft.notificationChannel()).isEqualTo("TELEGRAM_DM");
        assertThat(draft.notificationTargetAddress()).isEqualTo("123456789");
        assertThat(draft.missingFields()).isEmpty();
    }

    @Test
    @DisplayName("does not append channel question for unsupported MCP drafts")
    void doesNotAppendConversationFieldsForUnsupportedDrafts() {
        ParsedTaskNormalizer normalizer = new ParsedTaskNormalizer(returning(new DomainNormalizedSubscriptionDraft(
                "강남구 아파트 전세",
                "real-estate",
                "apartment_trade_price",
                null,
                Map.of("dealYmdPolicy", "LATEST_AVAILABLE_MONTH"),
                List.of("unsupportedCapability"),
                "현재는 아파트 매매 실거래가 알림만 만들 수 있어요.",
                0.9
        )));

        SubscriptionDraft draft = normalizer.normalize(
                realEstateTask("강남구 아파트 전세", "5% 이상 상승"),
                "강남구 아파트 전세 텔레그램으로 매일 알려줘"
        );

        assertThat(draft.cronExpr()).isNull();
        assertThat(draft.notificationChannel()).isNull();
        assertThat(draft.missingFields()).containsExactly("unsupportedCapability");
        assertThat(draft.assistantMessage()).contains("매매 실거래가");
    }

    private NormalizeSubscriptionDraftPort returning(DomainNormalizedSubscriptionDraft draft) {
        return (task, userMessage, previousDraft) -> Optional.of(draft);
    }

    private DomainNormalizedSubscriptionDraft domainDraft(
            String query,
            String toolName,
            Map<String, String> params,
            List<String> missing,
            String assistantMessage
    ) {
        return new DomainNormalizedSubscriptionDraft(
                query,
                "real-estate",
                "apartment_trade_price",
                toolName,
                params,
                missing,
                assistantMessage,
                0.91
        );
    }

    private ParsedTask realEstateTask(String query, String condition) {
        return new ParsedTask(
                "create",
                "부동산",
                query,
                condition,
                "0 9 * * *",
                "telegram",
                "api",
                query,
                List.of(),
                0.9,
                false,
                ""
        );
    }
}
