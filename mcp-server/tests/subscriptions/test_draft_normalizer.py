"""구독 초안 구조화 서비스 테스트."""

from mcp_server.subscriptions.draft_models import (
    ParsedTaskDraft,
    SubscriptionDraftNormalizationInput,
)
from mcp_server.subscriptions.draft_normalizer import normalize_subscription_draft


def test_ambiguous_apartment_change_requires_deal_type() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 아파트 변경 텔레그램으로 매일 오전 9시에 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 아파트 변경",
                condition="5% 이상 상승",
                cronExpr="0 9 * * *",
                channel="telegram",
                target="강남구 아파트 변경",
                confidence=0.9,
            ),
        )
    )

    assert result.domain_name == "real-estate"
    assert result.intent == "apartment_trade_price"
    assert result.tool_name is None
    assert result.parameters["region"] == "강남구"
    assert result.parameters["conditionThreshold"] == "5"
    assert result.missing_fields == ["dealType"]
    assert "매매" in result.question


def test_generic_apartment_price_requires_deal_type_before_condition() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 아파트 가격",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 아파트 가격",
                condition="",
                target="강남구 아파트 가격",
                confidence=0.9,
            ),
        )
    )

    assert result.domain_name == "real-estate"
    assert result.intent == "apartment_trade_price"
    assert result.tool_name is None
    assert result.parameters["region"] == "강남구"
    assert result.parameters["pendingDealTypeConfirmation"] == "true"
    assert result.missing_fields == ["dealType", "condition"]
    assert "매매" in result.question


def test_user_message_without_trade_stays_ambiguous_even_when_parser_query_infers_trade() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 아파트 가격",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 아파트 매매 실거래가",
                condition="1억 하락",
                target="강남구 아파트 매매 실거래가",
                confidence=0.9,
            ),
        )
    )

    assert result.tool_name is None
    assert result.parameters["region"] == "강남구"
    assert result.parameters["conditionThreshold"] == "1"
    assert result.parameters["conditionUnit"] == "EOK"
    assert result.missing_fields == ["dealType"]


def test_apartment_trade_request_returns_search_house_price_contract() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 아파트 매매 실거래가 5% 이상 상승하면 텔레그램으로 매일 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 아파트 매매 실거래가",
                condition="5% 이상 상승",
                cronExpr="0 9 * * *",
                channel="telegram",
                target="강남구 아파트 매매 실거래가",
                confidence=0.9,
            ),
        )
    )

    assert result.domain_name == "real-estate"
    assert result.intent == "apartment_trade_price"
    assert result.tool_name == "search_house_price"
    assert result.parameters["region"] == "강남구"
    assert result.parameters["conditionMetric"] == "AVG_PRICE"
    assert result.parameters["conditionDirection"] == "UP"
    assert result.parameters["conditionOperator"] == "GTE"
    assert result.parameters["conditionThreshold"] == "5"
    assert result.parameters["conditionUnit"] == "PERCENT"
    assert result.missing_fields == []


def test_apartment_rent_request_is_not_normalized_as_trade() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 아파트 전세 5% 이상 상승하면 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 아파트 전세",
                condition="5% 이상 상승",
                target="강남구 아파트 전세",
                confidence=0.9,
            ),
        )
    )

    assert result.tool_name is None
    assert result.missing_fields == ["unsupportedCapability"]
    assert "매매" in result.question


def test_inferred_target_rent_does_not_make_house_price_unsupported() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 집값",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 집값",
                condition="",
                cronExpr="0 9 * * *",
                channel="discord",
                target="서울특별시 강남구 지역의 아파트/주택 매매 및 전세 시세 변동",
                confidence=0.9,
                needsConfirmation=True,
            ),
        )
    )

    assert result.intent == "apartment_trade_price"
    assert result.tool_name is None
    assert result.parameters["region"] == "강남구"
    assert result.missing_fields == ["dealType", "condition"]


def test_inferred_target_price_does_not_resolve_ambiguous_change() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="강남구 아파트 변경 텔레그램으로 매일 오전 9시에 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query="강남구 아파트 변경",
                condition="5% 이상 상승",
                cronExpr="0 9 * * *",
                channel="telegram",
                target="강남구 아파트 가격 시세 변동",
                confidence=0.9,
            ),
        )
    )

    assert result.tool_name is None
    assert result.parameters["region"] == "강남구"
    assert result.missing_fields == ["dealType"]
