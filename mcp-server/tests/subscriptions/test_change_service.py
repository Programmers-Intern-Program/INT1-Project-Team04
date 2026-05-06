"""구독 스냅샷 지연 기준값 및 변화 비교 서비스 테스트."""

from contextlib import asynccontextmanager
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from mcp_server.db.models import SubscriptionSnapshotState
from mcp_server.subscriptions import change_service
from mcp_server.subscriptions.change_models import SubscriptionChangeInput
from mcp_server.subscriptions.change_service import (
    SubscriptionChangeService,
    extract_current_summary,
    stable_params_hash,
)


def _input(avg_deal_amount: int) -> SubscriptionChangeInput:
    return SubscriptionChangeInput.model_validate(
        {
            "subscriptionId": "42",
            "domain": "real-estate",
            "query": "강남구 아파트 매매",
            "params": {
                "region": "강남구",
                "condition": "평균 매매가가 5% 이상 상승하면 알려줘",
            },
            "current": {
                "text": f"평균 {avg_deal_amount}",
                "structured": {
                    "summary": {
                        "count": 20,
                        "avg_deal_amount": avg_deal_amount,
                    }
                },
            },
        }
    )


def _structured_condition_input(avg_deal_amount: int) -> SubscriptionChangeInput:
    input_model = _input(avg_deal_amount)
    input_model.params.update({
        "conditionMetric": "AVG_PRICE",
        "conditionDirection": "UP",
        "conditionOperator": "GTE",
        "conditionThreshold": "5",
        "conditionUnit": "PERCENT",
    })
    return input_model


def _recruitment_input(
    count: int,
    ongoing_count: int,
    *,
    metric: str = "COUNT",
    direction: str = "UP",
    threshold: str = "1",
    postings: list[dict] | None = None,
) -> SubscriptionChangeInput:
    # 채용 구독 조건은 current summary 의 공고 수 delta 를 기준으로 판별한다.
    structured = {
        "summary": {
            "count": count,
            "ongoing_count": ongoing_count,
        }
    }
    if postings is not None:
        structured["postings"] = postings

    return SubscriptionChangeInput.model_validate(
        {
            "subscriptionId": "job-42",
            "domain": "recruitment",
            "query": "백엔드 채용 공고",
            "params": {
                "keyword": "백엔드",
                "conditionMetric": metric,
                "conditionDirection": direction,
                "conditionOperator": "GTE",
                "conditionThreshold": threshold,
                "conditionUnit": "COUNT",
            },
            "current": {
                "text": f"채용 공고 {count}건",
                "structured": structured,
            },
        }
    )


def _posting(posting_id: str, *, is_ongoing: bool = True) -> dict:
    return {
        "pblnt_sn": posting_id,
        "title": f"{posting_id} 백엔드 개발자",
        "is_ongoing": is_ongoing,
    }


def test_stable_params_hash_ignores_key_order() -> None:
    left = stable_params_hash({"region": "강남구", "condition": "5% 상승"})
    right = stable_params_hash({"condition": "5% 상승", "region": "강남구"})

    assert left == right
    assert len(left) == 64


def test_extract_current_summary_prefers_structured_summary() -> None:
    summary = extract_current_summary(
        {
            "structured": {"summary": {"avg_deal_amount": 106000}},
        }
    )

    assert summary == {"avg_deal_amount": 106000}


async def test_first_run_initializes_baseline_without_diff(patched_session_factory) -> None:
    service = SubscriptionChangeService()

    result = await service.compare(_input(100000))

    assert result.baseline_initialized is True
    assert result.changed is False
    assert result.condition_satisfied is None
    assert result.requires_ai_analysis is False
    assert result.condition_reason == "baseline initialized"
    assert result.baseline_summary == {"count": 20, "avg_deal_amount": 100000}
    assert result.current_summary == {"count": 20, "avg_deal_amount": 100000}
    assert result.diffs == []

    async with patched_session_factory() as session:
        rows = (await session.execute(select(SubscriptionSnapshotState))).scalars().all()

    assert len(rows) == 1
    assert rows[0].subscription_id == "42"
    assert rows[0].baseline_summary == {"count": 20, "avg_deal_amount": 100000}


async def test_second_run_returns_numeric_diff_and_updates_latest(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(_input(100000))
    result = await service.compare(_input(106000))

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is None
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition missing"
    assert result.baseline_summary["avg_deal_amount"] == 100000
    assert result.current_summary["avg_deal_amount"] == 106000
    assert result.diffs[0].field == "avg_deal_amount"
    assert result.diffs[0].delta == 6000
    assert result.diffs[0].change_rate == 6.0
    assert result.diffs[0].direction == "increase"
    assert (
        "avg_deal_amount 값이 100000에서 106000으로 6000 증가했습니다."
        in result.briefing_facts
    )

    async with patched_session_factory() as session:
        row = (await session.execute(select(SubscriptionSnapshotState))).scalar_one()

    assert row.latest_summary == {"count": 20, "avg_deal_amount": 106000}


async def test_diff_below_structured_condition_does_not_require_ai_analysis(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(_structured_condition_input(100000))
    result = await service.compare(_structured_condition_input(102000))

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is False
    assert result.requires_ai_analysis is False
    assert result.condition_reason == "condition not satisfied"
    assert result.diffs[0].change_rate == 2.0


async def test_diff_meeting_structured_condition_requires_ai_analysis(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(_structured_condition_input(100000))
    result = await service.compare(_structured_condition_input(106000))

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is True
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition satisfied"
    assert result.diffs[0].change_rate == 6.0


async def test_recruitment_count_increase_meeting_condition_requires_ai_analysis(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(_recruitment_input(count=1, ongoing_count=1, metric="COUNT"))
    result = await service.compare(_recruitment_input(count=2, ongoing_count=1, metric="COUNT"))

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is True
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition satisfied"
    assert result.diffs[0].field == "count"
    assert result.diffs[0].delta == 1


async def test_recruitment_ongoing_count_increase_meeting_condition_requires_ai_analysis(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(_recruitment_input(count=3, ongoing_count=1, metric="ONGOING_COUNT", threshold="2"))
    result = await service.compare(_recruitment_input(count=3, ongoing_count=3, metric="ONGOING_COUNT", threshold="2"))

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is True
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition satisfied"
    assert result.diffs[0].field == "ongoing_count"
    assert result.diffs[0].delta == 2


async def test_recruitment_count_decrease_does_not_satisfy_up_condition(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(_recruitment_input(count=2, ongoing_count=2, metric="COUNT"))
    result = await service.compare(_recruitment_input(count=1, ongoing_count=1, metric="COUNT"))

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is False
    assert result.requires_ai_analysis is False
    assert result.condition_reason == "condition not satisfied"
    assert result.diffs[0].field == "count"
    assert result.diffs[0].direction == "decrease"


async def test_recruitment_detects_new_posting_ids_when_count_is_unchanged(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(
        _recruitment_input(
            count=2,
            ongoing_count=2,
            metric="COUNT",
            postings=[_posting("A"), _posting("B")],
        )
    )
    result = await service.compare(
        _recruitment_input(
            count=2,
            ongoing_count=2,
            metric="COUNT",
            postings=[_posting("B"), _posting("C")],
        )
    )

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is True
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition satisfied"
    assert result.diffs[0].field == "added_count"
    assert result.diffs[0].delta == 1


async def test_recruitment_detects_new_ongoing_posting_ids_when_ongoing_count_is_unchanged(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(
        _recruitment_input(
            count=2,
            ongoing_count=1,
            metric="ONGOING_COUNT",
            postings=[_posting("A", is_ongoing=True), _posting("B", is_ongoing=False)],
        )
    )
    result = await service.compare(
        _recruitment_input(
            count=2,
            ongoing_count=1,
            metric="ONGOING_COUNT",
            postings=[_posting("B", is_ongoing=False), _posting("C", is_ongoing=True)],
        )
    )

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is True
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition satisfied"
    ongoing_added_diff = next(diff for diff in result.diffs if diff.field == "ongoing_added_count")
    assert ongoing_added_diff.delta == 1


async def test_recruitment_detects_new_worknet_posting_ids_when_count_is_unchanged(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(
        _recruitment_input(
            count=1,
            ongoing_count=1,
            metric="COUNT",
            postings=[{"wanted_auth_no": "K120032605010001", "title": "백엔드 개발자"}],
        )
    )
    result = await service.compare(
        _recruitment_input(
            count=1,
            ongoing_count=1,
            metric="COUNT",
            postings=[{"wanted_auth_no": "K120032605020002", "title": "서버 개발자"}],
        )
    )

    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.condition_satisfied is True
    assert result.requires_ai_analysis is True
    assert result.condition_reason == "condition satisfied"
    assert result.diffs[0].field == "added_count"
    assert result.diffs[0].delta == 1


async def test_recruitment_ignores_unconfirmed_posting_id_fallback_keys(
    patched_session_factory,
) -> None:
    service = SubscriptionChangeService()

    await service.compare(
        _recruitment_input(
            count=1,
            ongoing_count=1,
            metric="COUNT",
            postings=[{"id": "A", "src_url": "https://example.com/a"}],
        )
    )
    result = await service.compare(
        _recruitment_input(
            count=1,
            ongoing_count=1,
            metric="COUNT",
            postings=[{"id": "B", "src_url": "https://example.com/b"}],
        )
    )

    assert result.baseline_initialized is False
    assert result.changed is False
    assert result.condition_satisfied is None
    assert result.requires_ai_analysis is False
    assert result.condition_reason == "no diff"


async def test_insert_race_rereads_row_and_compares(monkeypatch) -> None:
    params_hash = stable_params_hash(_input(106000).params)
    existing_row = SubscriptionSnapshotState(
        subscription_id="42",
        domain="real-estate",
        query="강남구 아파트 매매",
        params_hash=params_hash,
        baseline_summary={"count": 20, "avg_deal_amount": 100000},
        baseline_content="평균 100000",
        baseline_captured_at=datetime(2026, 5, 3, tzinfo=UTC),
        latest_summary={"count": 20, "avg_deal_amount": 100000},
        latest_content="평균 100000",
        latest_captured_at=datetime(2026, 5, 3, tzinfo=UTC),
    )
    session = _IntegrityRaceSession(existing_row)

    @asynccontextmanager
    async def fake_get_session():
        yield session

    monkeypatch.setattr(change_service, "get_session", fake_get_session)

    result = await SubscriptionChangeService().compare(_input(106000))

    assert session.rollback_called is True
    assert session.commit_calls == 2
    assert result.baseline_initialized is False
    assert result.changed is True
    assert result.baseline_summary == {"count": 20, "avg_deal_amount": 100000}
    assert result.current_summary == {"count": 20, "avg_deal_amount": 106000}
    assert result.diffs[0].field == "avg_deal_amount"
    assert existing_row.latest_summary == {"count": 20, "avg_deal_amount": 106000}


class _Result:
    def __init__(self, row: SubscriptionSnapshotState | None) -> None:
        self.row = row

    def scalar_one_or_none(self) -> SubscriptionSnapshotState | None:
        return self.row


class _IntegrityRaceSession:
    def __init__(self, existing_row: SubscriptionSnapshotState) -> None:
        self.existing_row = existing_row
        self.execute_calls = 0
        self.commit_calls = 0
        self.rollback_called = False

    async def execute(self, _statement):
        self.execute_calls += 1
        if self.execute_calls == 1:
            return _Result(None)
        return _Result(self.existing_row)

    def add(self, _row: SubscriptionSnapshotState) -> None:
        pass

    async def commit(self) -> None:
        self.commit_calls += 1
        if self.commit_calls == 1:
            raise IntegrityError("insert", {}, Exception("unique"))

    async def rollback(self) -> None:
        self.rollback_called = True
