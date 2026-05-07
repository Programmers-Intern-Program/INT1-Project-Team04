"""구독 스냅샷 저장 및 변화 비교 서비스."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from mcp_server.db.models import SubscriptionSnapshotState
from mcp_server.db.session import get_session
from mcp_server.subscriptions.change_models import (
    BriefingPosting,
    SubscriptionChangeInput,
    SubscriptionChangeResult,
    SummaryDiff,
)

AVG_PRICE_KEYS = frozenset({
    "avg_deal_amount",
    "avg_deposit",
    "avg_monthly_rent",
})
COUNT_KEYS = frozenset({"added_count", "count"})
ONGOING_COUNT_KEYS = frozenset({"ongoing_added_count", "ongoing_count"})
POSTING_IDS_FIELD = "posting_ids"
ONGOING_POSTING_IDS_FIELD = "ongoing_posting_ids"
INTERNAL_SUMMARY_FIELDS = frozenset({POSTING_IDS_FIELD, ONGOING_POSTING_IDS_FIELD})

# 현재는 ALIO/워크넷의 공식 공고 ID만 확정 계약으로 사용한다.
# URL/id 같은 범용 fallback 은 임시 추정값이라 오탐 위험이 있어 제외한다.
POSTING_ID_KEY_GROUPS = (
    ("public_job", ("pblnt_sn", "pblntSn", "recrutPblntSn", "recrut_pblnt_sn")),
    ("worknet_job", ("wanted_auth_no", "wantedAuthNo")),
)
POSTING_ONGOING_KEYS = ("is_ongoing", "isOngoing", "ongoing_yn", "ongoingYn")
BRIEFING_POSTING_SOURCES = ("public_job", "worknet_job")
POSTING_TITLE_KEYS = ("title", "wantedTitle", "recrutPbancTtl", "recrut_pbanc_ttl")
POSTING_URL_KEYS = ("src_url", "srcUrl", "info_url", "wantedInfoUrl")

# conditionMetric 과 current summary 필드를 분리해 도메인별 비교 대상이 섞이지 않게 한다.
METRIC_SUMMARY_KEYS = {
    "AVG_PRICE": AVG_PRICE_KEYS,
    "COUNT": COUNT_KEYS,
    "ONGOING_COUNT": ONGOING_COUNT_KEYS,
}
SUMMARY_FIELD_LABELS = {
    "avg_deal_amount": "평균 매매가",
    "min_deal_amount": "최저 매매가",
    "max_deal_amount": "최고 매매가",
    "avg_deposit": "평균 보증금",
    "min_deposit": "최저 보증금",
    "max_deposit": "최고 보증금",
    "avg_monthly_rent": "평균 월세",
    "count": "건수",
    "added_count": "신규 공고 수",
    "removed_count": "제외 공고 수",
    "ongoing_count": "진행중 공고 수",
    "ongoing_added_count": "신규 진행중 공고 수",
    "ongoing_removed_count": "제외된 진행중 공고 수",
}
MONEY_MANWON_FIELDS = frozenset({
    "avg_deal_amount",
    "min_deal_amount",
    "max_deal_amount",
    "avg_deposit",
    "min_deposit",
    "max_deposit",
    "avg_monthly_rent",
})
COUNT_FIELDS = COUNT_KEYS | ONGOING_COUNT_KEYS | frozenset({
    "removed_count",
    "ongoing_removed_count",
})


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


def normalize_current_response(current: dict[str, Any]) -> dict[str, Any]:
    """current.sources 계약을 단일 MCP 응답으로 병합해 AI 임의 합산을 막는다."""
    sources = current.get("sources")
    if not isinstance(sources, list):
        return current

    merged_postings: list[dict[str, Any]] = []
    merged_texts: list[str] = []
    total_count = 0
    total_ongoing_count = 0
    skipped_count = 0

    for source in sources:
        if not isinstance(source, dict):
            continue
        if is_permission_denied_source(source):
            skipped_count += 1
            continue

        structured = source.get("structured")
        structured = structured if isinstance(structured, dict) else {}
        summary = structured.get("summary")
        summary = summary if isinstance(summary, dict) else {}
        postings = structured.get("postings")
        postings = [posting for posting in postings if isinstance(posting, dict)] if isinstance(postings, list) else []

        total_count += summary_count(summary, "count", len(postings))
        total_ongoing_count += summary_count(
            summary,
            "ongoing_count",
            sum(1 for posting in postings if is_ongoing_posting(posting)),
        )
        merged_postings.extend(postings)

        text = source.get("text")
        if isinstance(text, str) and text.strip():
            merged_texts.append(text.strip())

    return {
        "text": "\n".join(merged_texts),
        "structured": {
            "summary": {
                "count": total_count,
                "ongoing_count": total_ongoing_count,
            },
            "postings": merged_postings,
            "postings_truncated": any_source_truncated(sources),
        },
        "source_url": None,
        "metadata": {
            "tool_name": "merged_recruitment_sources",
            "source_count": len(sources),
            "skipped_source_count": skipped_count,
        },
    }


def is_permission_denied_source(source: dict[str, Any]) -> bool:
    structured = source.get("structured")
    metadata = source.get("metadata")
    return (
        isinstance(structured, dict)
        and structured.get("permission_denied") is True
    ) or (
        isinstance(metadata, dict)
        and metadata.get("api_status") == "permission_denied"
    )


def summary_count(summary: dict[str, Any], field: str, default: int) -> int:
    value = summary.get(field)
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def any_source_truncated(sources: list[Any]) -> bool:
    for source in sources:
        if not isinstance(source, dict):
            continue
        structured = source.get("structured")
        if isinstance(structured, dict) and structured.get("postings_truncated") is True:
            return True
    return False


def enrich_summary_with_posting_ids(
    summary: dict[str, Any],
    current: dict[str, Any],
) -> dict[str, Any]:
    """채용 응답의 공고 ID 목록을 summary 에 보강해 count 동률 교체를 감지한다."""
    posting_id_sets = extract_posting_id_sets(current)
    if posting_id_sets is None:
        return dict(summary)

    posting_ids, ongoing_posting_ids = posting_id_sets
    enriched = dict(summary)
    enriched[POSTING_IDS_FIELD] = sorted(posting_ids)
    enriched[ONGOING_POSTING_IDS_FIELD] = sorted(ongoing_posting_ids)
    return enriched


def extract_posting_id_sets(current: dict[str, Any]) -> tuple[set[str], set[str]] | None:
    """structured.postings 에서 전체/진행중 공고 ID 집합을 추출한다."""
    structured = current.get("structured")
    if not isinstance(structured, dict):
        return None

    postings = structured.get("postings")
    if not isinstance(postings, list):
        return None

    posting_ids: set[str] = set()
    ongoing_posting_ids: set[str] = set()
    for posting in postings:
        if not isinstance(posting, dict):
            continue
        posting_id = posting_identity(posting)
        if posting_id is None:
            continue
        posting_ids.add(posting_id)
        if is_ongoing_posting(posting):
            ongoing_posting_ids.add(posting_id)
    return posting_ids, ongoing_posting_ids


def posting_identity(posting: dict[str, Any]) -> str | None:
    """공고별 안정 식별자를 만든다."""
    for namespace, keys in POSTING_ID_KEY_GROUPS:
        for key in keys:
            value = posting.get(key)
            normalized = normalize_posting_id_value(value)
            if normalized is not None:
                return f"{namespace}:{normalized}"
    return None


def posting_source(identity: str) -> str | None:
    source, separator, _ = identity.partition(":")
    if not separator or source not in BRIEFING_POSTING_SOURCES:
        return None
    return source


def normalize_posting_id_value(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def is_ongoing_posting(posting: dict[str, Any]) -> bool:
    """진행 여부 필드가 없으면 현재 조회 결과에 포함된 공고로 보고 진행중으로 취급한다."""
    for key in POSTING_ONGOING_KEYS:
        if key not in posting:
            continue
        value = posting[key]
        if isinstance(value, bool):
            return value
        if value is None:
            return True
        return str(value).strip().upper() != "N"
    return True


def empty_briefing_postings_by_source() -> dict[str, list[BriefingPosting]]:
    return {source: [] for source in BRIEFING_POSTING_SOURCES}


def build_briefing_postings_by_source(
    baseline_summary: dict[str, Any],
    current_summary: dict[str, Any],
    current: dict[str, Any],
) -> dict[str, list[BriefingPosting]]:
    """신규 채용 공고 ID를 현재 postings에서 찾아 AI 브리핑용 제목/링크로 묶는다."""
    result = empty_briefing_postings_by_source()
    baseline_ids = summary_id_set(baseline_summary, POSTING_IDS_FIELD)
    current_ids = summary_id_set(current_summary, POSTING_IDS_FIELD)
    if baseline_ids is None or current_ids is None:
        return result

    added_ids = current_ids - baseline_ids
    if not added_ids:
        return result

    structured = current.get("structured")
    if not isinstance(structured, dict):
        return result
    postings = structured.get("postings")
    if not isinstance(postings, list):
        return result

    for posting in postings:
        if not isinstance(posting, dict):
            continue
        identity = posting_identity(posting)
        if identity not in added_ids:
            continue
        source = posting_source(identity)
        if source is None:
            continue
        result[source].append(
            BriefingPosting(
                posting_id=identity,
                title=posting_title(posting),
                url=posting_url(posting),
            )
        )
    return result


def posting_title(posting: dict[str, Any]) -> str:
    for key in POSTING_TITLE_KEYS:
        value = posting.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return "(제목 없음)"


def posting_url(posting: dict[str, Any]) -> str | None:
    for key in POSTING_URL_KEYS:
        value = posting.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return None


class SubscriptionChangeService:
    """구독별 기준/최신 스냅샷을 저장하고 요약 변화 목록을 생성한다."""

    async def compare(self, input_model: SubscriptionChangeInput) -> SubscriptionChangeResult:
        now = datetime.now(UTC)
        params_hash = stable_params_hash(input_model.params)
        current = normalize_current_response(input_model.current)
        current_summary = enrich_summary_with_posting_ids(
            extract_current_summary(current),
            current,
        )
        current_content = current.get("text")
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
                        briefing_postings_by_source=empty_briefing_postings_by_source(),
                        condition_satisfied=None,
                        requires_ai_analysis=False,
                        condition_reason="baseline initialized",
                    )

            comparison_summary = snapshot_comparison_summary(input_model.domain, row)
            diffs = summary_diffs(comparison_summary, current_summary)
            briefing_postings_by_source = build_briefing_postings_by_source(
                comparison_summary,
                current_summary,
                current,
            )
            condition_satisfied, requires_ai_analysis, condition_reason = ai_analysis_gate(
                input_model.params,
                diffs,
            )
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
            baseline_summary=comparison_summary,
            current_summary=current_summary,
            diffs=diffs,
            briefing_facts=_briefing_facts(diffs),
            briefing_postings_by_source=briefing_postings_by_source,
            condition_satisfied=condition_satisfied,
            requires_ai_analysis=requires_ai_analysis,
            condition_reason=condition_reason,
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


def snapshot_comparison_summary(domain: str, row: SubscriptionSnapshotState) -> dict[str, Any]:
    """채용은 직전 실행 대비 신규 공고를, 그 외 도메인은 최초 baseline 대비 변화를 본다."""
    if domain == "recruitment" and isinstance(row.latest_summary, dict):
        return row.latest_summary
    return row.baseline_summary


def summary_diffs(
    baseline_summary: dict[str, Any],
    current_summary: dict[str, Any],
) -> list[SummaryDiff]:
    """요약 dict 의 공통 필드 중 값이 달라진 항목만 변화 목록으로 반환한다."""
    diffs = posting_id_diffs(baseline_summary, current_summary)
    for field in sorted(set(baseline_summary) & set(current_summary)):
        if field in INTERNAL_SUMMARY_FIELDS:
            continue
        baseline_value = baseline_summary[field]
        current_value = current_summary[field]
        if baseline_value == current_value:
            continue
        diffs.append(build_diff(field, baseline_value, current_value))
    return diffs


def posting_id_diffs(
    baseline_summary: dict[str, Any],
    current_summary: dict[str, Any],
) -> list[SummaryDiff]:
    """공고 ID 집합을 비교해 신규/제외 공고 수를 변화 목록으로 만든다."""
    diffs: list[SummaryDiff] = []
    diffs.extend(
        posting_set_diffs(
            baseline_summary,
            current_summary,
            POSTING_IDS_FIELD,
            "added_count",
            "removed_count",
        )
    )
    diffs.extend(
        posting_set_diffs(
            baseline_summary,
            current_summary,
            ONGOING_POSTING_IDS_FIELD,
            "ongoing_added_count",
            "ongoing_removed_count",
        )
    )
    return diffs


def posting_set_diffs(
    baseline_summary: dict[str, Any],
    current_summary: dict[str, Any],
    id_field: str,
    added_field: str,
    removed_field: str,
) -> list[SummaryDiff]:
    baseline_ids = summary_id_set(baseline_summary, id_field)
    current_ids = summary_id_set(current_summary, id_field)
    if baseline_ids is None or current_ids is None:
        return []

    # added_ids 는 baseline 에 없고 current 에 새로 등장한 공고 ID 집합이다.
    added_ids = current_ids - baseline_ids
    removed_ids = baseline_ids - current_ids
    diffs: list[SummaryDiff] = []
    if added_ids:
        diffs.append(build_diff(added_field, 0, len(added_ids)))
    if removed_ids:
        diffs.append(build_diff(removed_field, 0, len(removed_ids)))
    return diffs


def summary_id_set(summary: dict[str, Any], field: str) -> set[str] | None:
    value = summary.get(field)
    if not isinstance(value, list):
        return None
    return {str(item) for item in value}


def ai_analysis_gate(params: dict[str, Any], diffs: list[SummaryDiff]) -> tuple[bool | None, bool, str]:
    """코드로 판별 가능한 경우 AI 분석 대상인지 먼저 거른다."""
    if not diffs:
        return None, False, "no diff"

    condition = StructuredCondition.from_params(params)
    if condition is None:
        return None, True, "condition missing"

    satisfied = condition_satisfied(diffs, condition)
    if satisfied:
        return True, True, "condition satisfied"
    return False, False, "condition not satisfied"


@dataclass(frozen=True)
class StructuredCondition:
    metric: str
    direction: str
    operator: str
    threshold: Decimal
    unit: str

    @staticmethod
    def from_params(params: dict[str, Any]) -> StructuredCondition | None:
        try:
            metric = str(params["conditionMetric"])
            direction = str(params["conditionDirection"])
            operator = str(params["conditionOperator"])
            threshold = Decimal(str(params["conditionThreshold"]))
            unit = str(params["conditionUnit"])
        except (KeyError, InvalidOperation, TypeError, ValueError):
            return None
        return StructuredCondition(metric, direction, operator, threshold, unit)


def condition_satisfied(diffs: list[SummaryDiff], condition: StructuredCondition) -> bool:
    for diff in diffs:
        if not metric_matches(diff.field, condition.metric):
            continue
        if diff.delta is None:
            continue
        delta = Decimal(str(diff.delta))
        if not direction_matches(delta, condition.direction):
            continue
        comparable = comparable_value(diff, delta, condition.unit)
        if comparable is None:
            continue
        if operator_matches(comparable, threshold_value(condition), condition.operator):
            return True
    return False


def metric_matches(field: str, metric: str) -> bool:
    # summary_fields는 현재 conditionMetric이 비교할 summary 필드 집합이다.
    summary_fields = METRIC_SUMMARY_KEYS.get(metric)
    return summary_fields is not None and field in summary_fields


def direction_matches(delta: Decimal, direction: str) -> bool:
    if direction == "UP":
        return delta > 0
    if direction == "DOWN":
        return delta < 0
    if direction == "ANY":
        return delta != 0
    return False


def comparable_value(diff: SummaryDiff, delta: Decimal, unit: str) -> Decimal | None:
    if unit == "PERCENT":
        if diff.change_rate is None:
            return None
        return abs(Decimal(str(diff.change_rate)))
    return abs(delta)


def threshold_value(condition: StructuredCondition) -> Decimal:
    if condition.unit == "EOK":
        return condition.threshold * Decimal("10000")
    return condition.threshold


def operator_matches(comparable: Decimal, threshold: Decimal, operator: str) -> bool:
    if operator == "GTE":
        return comparable >= threshold
    if operator == "GT":
        return comparable > threshold
    if operator == "LTE":
        return comparable <= threshold
    if operator == "LT":
        return comparable < threshold
    return False


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
    label = SUMMARY_FIELD_LABELS.get(diff.field, diff.field)
    if diff.delta is not None:
        verb = "증가" if diff.direction == "increase" else "감소"
        return (
            f"{label}가 {_format_summary_value(diff.field, diff.baseline_value)}에서 "
            f"{_format_summary_value(diff.field, diff.current_value)}으로 "
            f"{_format_summary_value(diff.field, abs(diff.delta))} {verb}했습니다."
        )
    return (
        f"{label}가 {_format_summary_value(diff.field, diff.baseline_value)}에서 "
        f"{_format_summary_value(diff.field, diff.current_value)}으로 변경되었습니다."
    )


def _briefing_facts(diffs: list[SummaryDiff]) -> list[str]:
    facts: list[str] = []
    for diff in diffs:
        facts.append(briefing_fact(diff))
        if diff.change_rate is not None:
            label = SUMMARY_FIELD_LABELS.get(diff.field, diff.field)
            facts.append(f"{label} 변화율은 {diff.change_rate}%입니다.")
    return facts


def _is_number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _format_number(value: float) -> str:
    return str(int(value)) if value.is_integer() else str(value)


def _format_summary_value(field: str, value: Any) -> str:
    """알림 문장은 summary 내부 저장 단위보다 사용자가 읽는 단위를 우선한다."""
    if field in MONEY_MANWON_FIELDS and _is_number(value):
        return _format_manwon(float(value))
    if field in COUNT_FIELDS and _is_number(value):
        return f"{_format_number(float(value))}건"
    return str(value)


def _format_manwon(value: float) -> str:
    sign = "-" if value < 0 else ""
    amount = int(abs(value)) if float(value).is_integer() else abs(value)
    if amount < 10000:
        return f"{sign}{_format_amount(amount)}만원"

    eok = int(amount // 10000)
    manwon = amount - (eok * 10000)
    if manwon == 0:
        return f"{sign}{eok}억원"
    return f"{sign}{eok}억 {_format_amount(manwon)}만원"


def _format_amount(value: int | float) -> str:
    if isinstance(value, float) and not value.is_integer():
        return f"{value:,.1f}".rstrip("0").rstrip(".")
    return f"{int(value):,}"
