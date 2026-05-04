"""구독 스냅샷 저장 및 변화 비교 서비스."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from mcp_server.db.models import SubscriptionSnapshotState
from mcp_server.db.session import get_session
from mcp_server.subscriptions.change_models import (
    SubscriptionChangeInput,
    SubscriptionChangeResult,
    SummaryDiff,
)


def stable_params_hash(params: dict[str, Any]) -> str:
    """동일 params 가 항상 같은 감시 대상 키를 갖도록 안정 해시를 만든다."""
    normalized = json.dumps(
        params,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def extract_current_summary(current: dict[str, Any]) -> dict[str, Any]:
    """MCP 공통 응답에서 비교 가능한 요약 dict 를 추출한다."""
    structured = current.get("structured")
    if isinstance(structured, dict):
        summary = structured.get("summary")
        if isinstance(summary, dict):
            return summary
        if structured:
            return structured
    raise ValueError("current.structured 또는 current.structured.summary 가 필요합니다.")


class SubscriptionChangeService:
    """구독별 기준/최신 스냅샷을 저장하고 요약 변화 목록을 생성한다."""

    async def compare(self, input_model: SubscriptionChangeInput) -> SubscriptionChangeResult:
        now = datetime.now(UTC)
        params_hash = stable_params_hash(input_model.params)
        current_summary = extract_current_summary(input_model.current)
        current_content = input_model.current.get("text")
        current_content = current_content if isinstance(current_content, str) else None

        async with get_session() as session:
            result = await session.execute(
                select(SubscriptionSnapshotState).where(
                    SubscriptionSnapshotState.subscription_id == input_model.subscription_id,
                    SubscriptionSnapshotState.params_hash == params_hash,
                )
            )
            row = result.scalar_one_or_none()

            if row is None:
                row = SubscriptionSnapshotState(
                    subscription_id=input_model.subscription_id,
                    domain=input_model.domain,
                    query=input_model.query,
                    params_hash=params_hash,
                    baseline_summary=current_summary,
                    baseline_content=current_content,
                    baseline_captured_at=now,
                    latest_summary=current_summary,
                    latest_content=current_content,
                    latest_captured_at=now,
                )
                session.add(row)
                try:
                    await session.commit()
                except IntegrityError:
                    await session.rollback()
                    row = await self._get_snapshot_row(
                        session,
                        input_model.subscription_id,
                        params_hash,
                    )
                    if row is None:
                        raise
                else:
                    return SubscriptionChangeResult(
                        baseline_initialized=True,
                        changed=False,
                        subscription_id=input_model.subscription_id,
                        domain=input_model.domain,
                        params_hash=params_hash,
                        baseline_summary=current_summary,
                        current_summary=current_summary,
                        diffs=[],
                        briefing_facts=[],
                    )

            baseline_summary = row.baseline_summary
            diffs = summary_diffs(baseline_summary, current_summary)
            row.latest_summary = current_summary
            row.latest_content = current_content
            row.latest_captured_at = now
            await session.commit()

        return SubscriptionChangeResult(
            baseline_initialized=False,
            changed=bool(diffs),
            subscription_id=input_model.subscription_id,
            domain=input_model.domain,
            params_hash=params_hash,
            baseline_summary=baseline_summary,
            current_summary=current_summary,
            diffs=diffs,
            briefing_facts=_briefing_facts(diffs),
        )

    async def _get_snapshot_row(
        self,
        session: Any,
        subscription_id: str,
        params_hash: str,
    ) -> SubscriptionSnapshotState | None:
        result = await session.execute(
            select(SubscriptionSnapshotState).where(
                SubscriptionSnapshotState.subscription_id == subscription_id,
                SubscriptionSnapshotState.params_hash == params_hash,
            )
        )
        return result.scalar_one_or_none()


def summary_diffs(
    baseline_summary: dict[str, Any],
    current_summary: dict[str, Any],
) -> list[SummaryDiff]:
    """요약 dict 의 공통 필드 중 값이 달라진 항목만 변화 목록으로 반환한다."""
    diffs: list[SummaryDiff] = []
    for field in sorted(set(baseline_summary) & set(current_summary)):
        baseline_value = baseline_summary[field]
        current_value = current_summary[field]
        if baseline_value == current_value:
            continue
        diffs.append(build_diff(field, baseline_value, current_value))
    return diffs


def build_diff(field: str, baseline_value: Any, current_value: Any) -> SummaryDiff:
    """숫자 필드는 증감값/변화율을 계산하고, 그 외 필드는 변경됨으로 표시한다."""
    if _is_number(baseline_value) and _is_number(current_value):
        baseline_number = float(baseline_value)
        current_number = float(current_value)
        delta = current_number - baseline_number
        change_rate = round((delta / baseline_number) * 100, 2) if baseline_number else None
        direction = "increase" if delta > 0 else "decrease"
        return SummaryDiff(
            field=field,
            baseline_value=baseline_value,
            current_value=current_value,
            delta=delta,
            change_rate=change_rate,
            direction=direction,
        )
    return SummaryDiff(
        field=field,
        baseline_value=baseline_value,
        current_value=current_value,
        direction="changed",
    )


def briefing_fact(diff: SummaryDiff) -> str:
    """AI 브리핑에 바로 사용할 수 있는 대표 근거 문장을 만든다."""
    if diff.delta is not None:
        verb = "증가" if diff.direction == "increase" else "감소"
        return (
            f"{diff.field} 값이 {diff.baseline_value}에서 {diff.current_value}으로 "
            f"{_format_number(diff.delta)} {verb}했습니다."
        )
    return f"{diff.field} 값이 {diff.baseline_value}에서 {diff.current_value}으로 변경되었습니다."


def _briefing_facts(diffs: list[SummaryDiff]) -> list[str]:
    facts: list[str] = []
    for diff in diffs:
        facts.append(briefing_fact(diff))
        if diff.change_rate is not None:
            facts.append(f"{diff.field} 변화율은 {diff.change_rate}%입니다.")
    return facts


def _is_number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _format_number(value: float) -> str:
    return str(int(value)) if value.is_integer() else str(value)
