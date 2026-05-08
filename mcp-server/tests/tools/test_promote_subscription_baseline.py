"""promote_subscription_baseline 도구 응답 스키마 테스트."""

from datetime import UTC, datetime

from mcp_server.subscriptions.baseline_promote_models import (
    PromoteSubscriptionBaselineInput,
    PromoteSubscriptionBaselineResult,
)
from mcp_server.tools import subscriptions


async def test_promote_subscription_baseline_returns_common_schema(monkeypatch) -> None:
    async def fake_promote(_, input_model: PromoteSubscriptionBaselineInput):
        return PromoteSubscriptionBaselineResult(
            promoted=True,
            subscription_id=input_model.subscription_id,
            rows_updated=2,
            promoted_at=datetime(2026, 5, 8, 10, 30, tzinfo=UTC),
        )

    monkeypatch.setattr(subscriptions.BaselinePromoteService, "promote", fake_promote)

    response = await subscriptions.promote_subscription_baseline(
        PromoteSubscriptionBaselineInput.model_validate(
            {"subscriptionId": "42", "paramsHash": "hash-a"}
        )
    )

    assert response["text"] == "구독 42 baseline 갱신 완료 (2건)."
    assert response["structured"]["promoted"] is True
    assert response["structured"]["subscriptionId"] == "42"
    assert response["structured"]["rows_updated"] == 2
    assert response["source_url"] is None
    assert response["metadata"]["tool_name"] == "promote_subscription_baseline"
    assert response["metadata"]["subscription_id"] == "42"
    assert response["metadata"]["params_hash"] == "hash-a"
    assert response["metadata"]["rows_updated"] == 2


async def test_promote_subscription_baseline_skipped_text(monkeypatch) -> None:
    async def fake_promote(_, input_model: PromoteSubscriptionBaselineInput):
        return PromoteSubscriptionBaselineResult(
            promoted=False,
            subscription_id=input_model.subscription_id,
            rows_updated=0,
            promoted_at=datetime(2026, 5, 8, 10, 30, tzinfo=UTC),
            skipped_reason="snapshot row not found",
        )

    monkeypatch.setattr(subscriptions.BaselinePromoteService, "promote", fake_promote)

    response = await subscriptions.promote_subscription_baseline(
        PromoteSubscriptionBaselineInput.model_validate({"subscriptionId": "missing"})
    )

    assert response["text"] == "구독 missing baseline 갱신 건너뜀: snapshot row not found."
    assert response["structured"]["promoted"] is False
    assert response["structured"]["skipped_reason"] == "snapshot row not found"
