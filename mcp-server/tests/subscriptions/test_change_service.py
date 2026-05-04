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
