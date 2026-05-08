"""baseline 승격 서비스 단위 테스트."""

from datetime import UTC, datetime

from sqlalchemy import select

from mcp_server.db.models import SubscriptionSnapshotState
from mcp_server.subscriptions.baseline_promote_models import (
    PromoteSubscriptionBaselineInput,
)
from mcp_server.subscriptions.baseline_promote_service import BaselinePromoteService


async def _insert_row(
    factory,
    *,
    subscription_id: str,
    params_hash: str,
    baseline_summary: dict,
    latest_summary: dict | None,
    baseline_content: str | None = "baseline-content",
    latest_content: str | None = "latest-content",
) -> None:
    async with factory() as session:
        session.add(
            SubscriptionSnapshotState(
                subscription_id=subscription_id,
                domain="real-estate",
                query="강남구 아파트 매매",
                params_hash=params_hash,
                baseline_summary=baseline_summary,
                baseline_content=baseline_content,
                baseline_captured_at=datetime(2026, 5, 1, tzinfo=UTC),
                latest_summary=latest_summary,
                latest_content=latest_content if latest_summary else None,
                latest_captured_at=datetime(2026, 5, 7, tzinfo=UTC) if latest_summary else None,
            )
        )
        await session.commit()


async def test_promote_copies_latest_into_baseline(patched_session_factory) -> None:
    await _insert_row(
        patched_session_factory,
        subscription_id="42",
        params_hash="hash-a",
        baseline_summary={"avg_deal_amount": 100000},
        latest_summary={"avg_deal_amount": 106000},
    )

    result = await BaselinePromoteService().promote(
        PromoteSubscriptionBaselineInput.model_validate({"subscriptionId": "42"})
    )

    assert result.promoted is True
    assert result.rows_updated == 1
    assert result.skipped_reason is None

    async with patched_session_factory() as session:
        row = (await session.execute(select(SubscriptionSnapshotState))).scalar_one()

    assert row.baseline_summary == {"avg_deal_amount": 106000}
    assert row.baseline_content == "latest-content"
    # SQLite 는 timezone 정보를 보존하지 않으므로 naive 비교 (실제 운영 PG 에서는 TIMESTAMPTZ).
    assert row.baseline_captured_at.replace(tzinfo=None) == datetime(2026, 5, 7)


async def test_promote_with_params_hash_targets_single_row(patched_session_factory) -> None:
    await _insert_row(
        patched_session_factory,
        subscription_id="42",
        params_hash="hash-a",
        baseline_summary={"avg_deal_amount": 100000},
        latest_summary={"avg_deal_amount": 106000},
    )
    await _insert_row(
        patched_session_factory,
        subscription_id="42",
        params_hash="hash-b",
        baseline_summary={"avg_deal_amount": 200000},
        latest_summary={"avg_deal_amount": 210000},
    )

    result = await BaselinePromoteService().promote(
        PromoteSubscriptionBaselineInput.model_validate(
            {"subscriptionId": "42", "paramsHash": "hash-a"}
        )
    )

    assert result.promoted is True
    assert result.rows_updated == 1

    async with patched_session_factory() as session:
        rows = (
            (
                await session.execute(
                    select(SubscriptionSnapshotState).order_by(SubscriptionSnapshotState.params_hash)
                )
            )
            .scalars()
            .all()
        )

    # hash-a 만 갱신, hash-b 는 그대로
    assert rows[0].params_hash == "hash-a"
    assert rows[0].baseline_summary == {"avg_deal_amount": 106000}
    assert rows[1].params_hash == "hash-b"
    assert rows[1].baseline_summary == {"avg_deal_amount": 200000}


async def test_promote_returns_skipped_when_no_row(patched_session_factory) -> None:
    result = await BaselinePromoteService().promote(
        PromoteSubscriptionBaselineInput.model_validate({"subscriptionId": "missing"})
    )

    assert result.promoted is False
    assert result.rows_updated == 0
    assert result.skipped_reason == "snapshot row not found"


async def test_promote_skips_row_without_latest_summary(patched_session_factory) -> None:
    await _insert_row(
        patched_session_factory,
        subscription_id="42",
        params_hash="hash-a",
        baseline_summary={"avg_deal_amount": 100000},
        latest_summary=None,
    )

    result = await BaselinePromoteService().promote(
        PromoteSubscriptionBaselineInput.model_validate({"subscriptionId": "42"})
    )

    assert result.promoted is False
    assert result.rows_updated == 0
    assert result.skipped_reason == "no row had latest_summary"

    async with patched_session_factory() as session:
        row = (await session.execute(select(SubscriptionSnapshotState))).scalar_one()

    # baseline 은 변경되지 않아야 한다
    assert row.baseline_summary == {"avg_deal_amount": 100000}


async def test_promote_without_params_hash_updates_all_rows(patched_session_factory) -> None:
    await _insert_row(
        patched_session_factory,
        subscription_id="42",
        params_hash="hash-a",
        baseline_summary={"avg_deal_amount": 100000},
        latest_summary={"avg_deal_amount": 106000},
    )
    await _insert_row(
        patched_session_factory,
        subscription_id="42",
        params_hash="hash-b",
        baseline_summary={"avg_deal_amount": 200000},
        latest_summary={"avg_deal_amount": 210000},
    )

    result = await BaselinePromoteService().promote(
        PromoteSubscriptionBaselineInput.model_validate({"subscriptionId": "42"})
    )

    assert result.promoted is True
    assert result.rows_updated == 2
