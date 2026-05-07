"""구독 초안 구조화 서비스 테스트."""

import pytest

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


def test_recruitment_new_job_request_returns_job_posting_change_contract() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="백엔드 채용 새 공고 뜨면 텔레그램으로 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="채용",
                query="백엔드 채용 새 공고",
                condition="새 공고 등록",
                cronExpr="0 9 * * *",
                channel="telegram",
                target="백엔드 채용 공고",
                confidence=0.9,
            ),
        )
    )

    assert result.domain_name == "recruitment"
    assert result.intent == "job_posting_change"
    assert result.tool_name in {"search_public_job", "search_worknet_job"}
    assert result.parameters["dataToolName"] == result.tool_name
    assert result.parameters["keyword"] == "백엔드"
    assert result.parameters["conditionMetric"] == "COUNT"
    assert result.parameters["conditionDirection"] == "UP"
    assert result.parameters["conditionOperator"] == "GTE"
    assert result.parameters["conditionThreshold"] == "1"
    assert result.parameters["conditionUnit"] == "COUNT"
    assert result.missing_fields == []


def test_recruitment_time_number_does_not_override_default_count_threshold() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="백엔드 채용 새 공고 뜨면 매일 오전 9시에 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="채용",
                query="백엔드 채용 새 공고",
                condition="새 공고 등록",
                cronExpr="0 9 * * *",
                channel="telegram",
                target="백엔드 채용 공고",
                confidence=0.9,
            ),
        )
    )

    assert result.parameters["keyword"] == "백엔드"
    assert result.parameters["conditionMetric"] == "COUNT"
    assert result.parameters["conditionThreshold"] == "1"


def test_public_recruitment_ongoing_request_uses_public_job_and_ongoing_count() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="공공기관 데이터 채용 진행중 공고가 늘면 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="채용",
                query="공공기관 데이터 채용",
                condition="진행중 공고 증가",
                target="공공기관 데이터 채용 진행중 공고",
                confidence=0.9,
            ),
        )
    )

    assert result.domain_name == "recruitment"
    assert result.intent == "job_posting_change"
    assert result.tool_name == "search_public_job"
    assert result.parameters["dataToolName"] == "search_public_job"
    assert result.parameters["keyword"] == "데이터"
    assert result.parameters["recrut_pbanc_ttl"] == "데이터"
    assert result.parameters["ongoing_yn"] == "Y"
    assert result.parameters["conditionMetric"] == "ONGOING_COUNT"
    assert result.parameters["conditionThreshold"] == "1"
    assert result.parameters["conditionUnit"] == "COUNT"
    assert result.missing_fields == []


def test_recruitment_request_without_target_or_condition_asks_for_more_details() -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage="채용 알려줘",
            task=ParsedTaskDraft(
                intent="create",
                domainName="채용",
                query="채용",
                condition="",
                target="채용",
                confidence=0.7,
                needsConfirmation=True,
            ),
        )
    )

    assert result.domain_name == "recruitment"
    assert result.intent == "job_posting_change"
    assert result.missing_fields == ["keyword", "condition"]
    assert "어떤 채용" in result.question


@pytest.mark.parametrize(
    ("user_message", "query", "target", "expected_intent", "expected_tool_name"),
    [
        (
            "강남구 아파트 전월세 보증금 5% 이상 상승하면 알려줘",
            "강남구 아파트 전월세",
            "강남구 아파트 전월세",
            "apartment_rent_price",
            "search_apt_rent",
        ),
        (
            "강남구 오피스텔 매매 실거래가 5% 이상 상승하면 알려줘",
            "강남구 오피스텔 매매 실거래가",
            "강남구 오피스텔 매매 실거래가",
            "officetel_trade_price",
            "search_offi_trade",
        ),
        (
            "강남구 오피스텔 전월세 보증금 5% 이상 상승하면 알려줘",
            "강남구 오피스텔 전월세",
            "강남구 오피스텔 전월세",
            "officetel_rent_price",
            "search_offi_rent",
        ),
        (
            "강남구 연립다세대 매매 실거래가 5% 이상 상승하면 알려줘",
            "강남구 연립다세대 매매 실거래가",
            "강남구 연립다세대 매매 실거래가",
            "row_house_trade_price",
            "search_rh_trade",
        ),
        (
            "강남구 연립다세대 전월세 보증금 5% 이상 상승하면 알려줘",
            "강남구 연립다세대 전월세",
            "강남구 연립다세대 전월세",
            "row_house_rent_price",
            "search_rh_rent",
        ),
    ],
)
def test_supported_real_estate_capabilities_return_matching_tool_contract(
    user_message: str,
    query: str,
    target: str,
    expected_intent: str,
    expected_tool_name: str,
) -> None:
    result = normalize_subscription_draft(
        SubscriptionDraftNormalizationInput(
            userMessage=user_message,
            task=ParsedTaskDraft(
                intent="create",
                domainName="부동산",
                query=query,
                condition="5% 이상 상승",
                target=target,
                confidence=0.9,
            ),
        )
    )

    assert result.domain_name == "real-estate"
    assert result.intent == expected_intent
    assert result.tool_name == expected_tool_name
    assert result.parameters["region"] == "강남구"
    assert result.parameters["dealYmdPolicy"] == "LATEST_AVAILABLE_MONTH"
    assert result.parameters["conditionMetric"] == "AVG_PRICE"
    assert result.parameters["conditionDirection"] == "UP"
    assert result.parameters["conditionOperator"] == "GTE"
    assert result.parameters["conditionThreshold"] == "5"
    assert result.parameters["conditionUnit"] == "PERCENT"
    assert result.missing_fields == []


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
