"""구독 변화 비교 MCP 도구 응답 스키마 테스트."""

from mcp_server.subscriptions.change_models import (
    SubscriptionChangeInput,
    SubscriptionChangeResult,
)
from mcp_server.tools import subscriptions


async def test_compare_subscription_change_returns_common_schema(monkeypatch) -> None:
    async def fake_compare(_, input_model: SubscriptionChangeInput) -> SubscriptionChangeResult:
        return SubscriptionChangeResult(
            baseline_initialized=False,
            changed=True,
            subscription_id=input_model.subscription_id,
            domain=input_model.domain,
            params_hash="hash-42",
            baseline_summary={"avg_deal_amount": 100000},
            current_summary={"avg_deal_amount": 106000},
            diffs=[],
            briefing_facts=["avg_deal_amount 값이 100000에서 106000으로 증가했습니다."],
        )

    monkeypatch.setattr(subscriptions.SubscriptionChangeService, "compare", fake_compare)

    response = await subscriptions.compare_subscription_change(
        SubscriptionChangeInput.model_validate(
            {
                "subscriptionId": "42",
                "domain": "real-estate",
                "query": "강남구 아파트 매매",
                "params": {"region": "강남구"},
                "current": {
                    "text": "평균 106000",
                    "structured": {"summary": {"avg_deal_amount": 106000}},
                },
            }
        )
    )

    assert response["text"] == "구독 42 변화 감지: 변경 있음."
    assert response["structured"]["subscriptionId"] == "42"
    assert response["structured"]["changed"] is True
    assert response["structured"]["params_hash"] == "hash-42"
    assert response["source_url"] is None
    assert response["metadata"] == {
        "tool_name": "compare_subscription_change",
        "subscription_id": "42",
        "params_hash": "hash-42",
    }
