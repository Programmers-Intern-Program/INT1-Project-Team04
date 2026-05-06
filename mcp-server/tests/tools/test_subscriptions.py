"""구독 변화 비교 MCP 도구 응답 스키마 테스트."""

from mcp_server.subscriptions.change_models import (
    SubscriptionChangeInput,
    SubscriptionChangeResult,
)
from mcp_server.subscriptions.draft_models import (
    ParsedTaskDraft,
    SubscriptionDraftNormalizationInput,
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
            briefing_postings_by_source={
                "public_job": [{
                    "posting_id": "public_job:1",
                    "title": "백엔드 개발자",
                    "url": "https://public.example/jobs/1",
                }],
                "worknet_job": [],
            },
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
    assert response["structured"]["requires_ai_analysis"] is False
    assert response["structured"]["briefing_postings_by_source"] == {
        "public_job": [{
            "posting_id": "public_job:1",
            "title": "백엔드 개발자",
            "url": "https://public.example/jobs/1",
        }],
        "worknet_job": [],
    }
    assert response["structured"]["params_hash"] == "hash-42"
    assert response["source_url"] is None
    assert response["metadata"] == {
        "tool_name": "compare_subscription_change",
        "subscription_id": "42",
        "params_hash": "hash-42",
    }


async def test_compare_subscription_change_accepts_current_sources_contract(monkeypatch) -> None:
    captured_sources: list[dict] = []

    async def fake_compare(_, input_model: SubscriptionChangeInput) -> SubscriptionChangeResult:
        captured_sources.extend(input_model.current["sources"])
        return SubscriptionChangeResult(
            baseline_initialized=False,
            changed=False,
            subscription_id=input_model.subscription_id,
            domain=input_model.domain,
            params_hash="hash-job",
            baseline_summary={"count": 1},
            current_summary={"count": 1},
            diffs=[],
            briefing_facts=[],
            briefing_postings_by_source={"public_job": [], "worknet_job": []},
        )

    monkeypatch.setattr(subscriptions.SubscriptionChangeService, "compare", fake_compare)

    response = await subscriptions.compare_subscription_change(
        SubscriptionChangeInput.model_validate(
            {
                "subscriptionId": "job-42",
                "domain": "recruitment",
                "query": "백엔드 채용 공고",
                "params": {"keyword": "백엔드"},
                "current": {
                    "sources": [{
                        "text": "공공채용 1건",
                        "structured": {"summary": {"count": 1}, "postings": []},
                        "metadata": {"tool_name": "search_public_job"},
                    }],
                },
            }
        )
    )

    assert captured_sources[0]["metadata"]["tool_name"] == "search_public_job"
    assert response["structured"]["changed"] is False
    assert response["metadata"]["params_hash"] == "hash-job"


async def test_normalize_subscription_draft_returns_common_schema() -> None:
    response = await subscriptions.normalize_subscription_draft(
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

    assert response["text"] == "구독 초안 구조화에 추가 정보가 필요합니다."
    assert response["structured"]["domainName"] == "real-estate"
    assert response["structured"]["intent"] == "apartment_trade_price"
    assert response["structured"]["toolName"] is None
    assert response["structured"]["parameters"]["region"] == "강남구"
    assert response["structured"]["missingFields"] == ["dealType"]
    assert response["source_url"] is None
    assert response["metadata"] == {
        "tool_name": "normalize_subscription_draft",
        "missing_fields": ["dealType"],
    }
