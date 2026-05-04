"""구독 변화 비교 MCP 입력/출력 모델 테스트."""

from pydantic import ValidationError

from mcp_server.subscriptions.change_models import (
    SubscriptionChangeInput,
    SubscriptionChangeResult,
    SummaryDiff,
)


def test_change_input_accepts_backend_subscription_id_alias() -> None:
    input_model = SubscriptionChangeInput.model_validate(
        {
            "subscriptionId": "42",
            "domain": "real-estate",
            "query": "강남구 아파트 매매",
            "params": {"condition": "5% 이상 상승"},
            "current": {
                "text": "현재 요약",
                "structured": {"summary": {"avg_deal_amount": 106000}},
            },
        }
    )

    assert input_model.subscription_id == "42"
    assert input_model.domain == "real-estate"
    assert input_model.params["condition"] == "5% 이상 상승"


def test_change_input_accepts_python_subscription_id_name() -> None:
    input_model = SubscriptionChangeInput.model_validate(
        {
            "subscription_id": "42",
            "domain": "real-estate",
            "params": {},
            "current": {"structured": {"summary": {"count": 20}}},
        }
    )

    assert input_model.subscription_id == "42"


def test_summary_diff_direction_for_increase() -> None:
    diff = SummaryDiff(
        field="avg_deal_amount",
        baseline_value=100000,
        current_value=106000,
        delta=6000,
        change_rate=6.0,
        direction="increase",
    )

    assert diff.direction == "increase"
    assert diff.change_rate == 6.0


def test_summary_diff_rejects_unknown_direction() -> None:
    try:
        SummaryDiff(
            field="avg_deal_amount",
            baseline_value=100000,
            current_value=106000,
            direction="same",
        )
    except ValidationError as exc:
        assert "increase" in str(exc)
    else:
        raise AssertionError("SummaryDiff accepted an unsupported direction")


def test_change_result_serializes_subscription_id_alias() -> None:
    result = SubscriptionChangeResult(
        baseline_initialized=False,
        changed=True,
        subscription_id="42",
        domain="real-estate",
        params_hash="hash-42",
        baseline_summary={"avg_deal_amount": 100000},
        current_summary={"avg_deal_amount": 106000},
        diffs=[
            SummaryDiff(
                field="avg_deal_amount",
                baseline_value=100000,
                current_value=106000,
                delta=6000,
                change_rate=6.0,
                direction="increase",
            )
        ],
        briefing_facts=["avg_deal_amount 값이 증가했습니다."],
    )

    dumped = result.model_dump(by_alias=True)

    assert dumped["subscriptionId"] == "42"
    assert "subscription_id" not in dumped
