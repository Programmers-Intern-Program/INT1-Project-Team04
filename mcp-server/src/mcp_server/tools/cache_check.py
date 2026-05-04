"""캐시 상태 확인 및 캐시 직접 읽기 MCP 도구.

등록 도구 (2종):
- check_api_cache  — staleness 센서. fetch tool 호출 전 캐시 상태 확인.
- get_cached_data  — 캐시에서 직접 데이터 읽기. 외부 API 미호출.
"""

from datetime import date, datetime
from typing import Any, Callable

from mcp_server.domains.auction.normalizer import normalize_g2b_bid
from mcp_server.domains.jobs.errors import WorknetPermissionDeniedError
from mcp_server.domains.jobs.normalizer import normalize_public_job, normalize_worknet_job
from mcp_server.domains.law.normalizer import normalize_bill_info, normalize_law_info
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service

_MAX_RESULTS = 20


# ─────────────────────────────────────────────
# 도구 1: check_api_cache
# ─────────────────────────────────────────────


@mcp.tool()
@traced("check_api_cache")
async def check_api_cache(tool_name: str) -> dict[str, Any]:
    """fetch tool 호출 전 반드시 이 tool을 먼저 호출해 캐시 상태를 확인하라.

    반환된 cache_hit + last_fetched_at + tool_name(도메인 맥락)으로 fetch 필요 여부를 판단.
    - cache_hit: false  → search_* fetch tool 호출 필요
    - cache_hit: true, 신선 → get_cached_data(tool_name) 호출 (외부 API 미호출)
    - cache_hit: true, stale → search_* fetch tool 호출 필요

    freshness 기준은 도메인 특성에 따라 직접 판단할 것.
    법률·의안=주 단위, 채용=일 단위, 경매=시간 단위 등.
    부동산 6종(search_house_price 등)은 지원하지 않음.

    Args:
        tool_name: 예: "search_law_info", "search_g2b_bid".
    """
    cached_at: datetime | None = await api_source_service.peek_cached_at(tool_name)

    if cached_at is None:
        return {"cache_hit": False, "last_fetched_at": None, "tool_name": tool_name}

    return {
        "cache_hit": True,
        "last_fetched_at": cached_at.isoformat(),
        "tool_name": tool_name,
    }


# ─────────────────────────────────────────────
# 도구 2: get_cached_data
# ─────────────────────────────────────────────


@mcp.tool()
@traced("get_cached_data")
async def get_cached_data(tool_name: str) -> dict[str, Any]:
    """check_api_cache 결과가 cache_hit: true이고 신선하다고 판단할 때만 호출.

    캐시에서 데이터를 직접 읽어 정규화된 결과를 반환한다. 외부 API를 호출하지 않는다.
    응답 포맷은 fetch tool과 동일 (text, structured, source_url, metadata).
    metadata.cache_used: true 로 캐시 응답임을 표시.

    캐시 없음 또는 지원하지 않는 tool_name → cache_hit: false 응답.
    부동산 6종은 지원하지 않음.

    Args:
        tool_name: 예: "search_law_info", "search_g2b_bid".
    """
    result = await api_source_service.peek_cached_content(tool_name)
    if result is None:
        return {
            "cache_hit": False,
            "tool_name": tool_name,
            "message": f"캐시 없음. {tool_name}을 먼저 호출하거나 check_api_cache를 확인하세요.",
        }

    content, cached_at = result
    formatter = _FORMATTER_REGISTRY.get(tool_name)
    if formatter is None:
        return {
            "cache_hit": False,
            "tool_name": tool_name,
            "message": f"{tool_name}은 get_cached_data 미지원 도구입니다.",
        }

    return formatter(content, cached_at)


# ─────────────────────────────────────────────
# 도메인별 formatter
# ─────────────────────────────────────────────


def _format_law_info(content: str, cached_at: datetime) -> dict[str, Any]:
    records = normalize_law_info(content)
    promulgation_dates = [r.promulgation_date for r in records if r.promulgation_date]
    ministries = sorted({r.ministry_name for r in records if r.ministry_name})
    summary = {
        "count": len(records),
        "latest_promulgation_date": (
            max(promulgation_dates).isoformat() if promulgation_dates else None
        ),
        "ministries": ministries,
    }
    sorted_records = sorted(records, key=lambda r: r.promulgation_date or date.min, reverse=True)
    returned = sorted_records[:_MAX_RESULTS]

    count = summary["count"]
    text = (
        f"현행법령 {count}건 (캐시). 최신 공포 {summary['latest_promulgation_date'] or '-'}."
        if count > 0 else "현행법령 0건 (캐시)."
    )
    return {
        "text": text,
        "structured": {
            "summary": summary,
            "laws": [r.model_dump(mode="json") for r in returned],
            "laws_truncated": len(sorted_records) > _MAX_RESULTS,
            "query": {"query": "*", "page_no": 1, "num_of_rows": _MAX_RESULTS},
        },
        "source_url": None,
        "metadata": {
            "fetched_at": cached_at.isoformat(),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": "search_law_info",
            "cache_used": True,
        },
    }


def _format_bill_info(content: str, cached_at: datetime) -> dict[str, Any]:
    records = normalize_bill_info(content)
    propose_dates = [r.propose_date for r in records if r.propose_date]
    committees = sorted({r.committee for r in records if r.committee})
    ages = [r.age for r in records if r.age]
    summary = {
        "count": len(records),
        "latest_propose_date": max(propose_dates).isoformat() if propose_dates else None,
        "committees": committees,
    }
    sorted_records = sorted(records, key=lambda r: r.propose_date or date.min, reverse=True)
    returned = sorted_records[:_MAX_RESULTS]

    count = summary["count"]
    age_str = f"제{max(ages)}대 " if ages else ""
    text = (
        f"{age_str}의안 {count}건 (캐시). 최근 발의 {summary['latest_propose_date'] or '-'}."
        if count > 0 else "의안 0건 (캐시)."
    )
    return {
        "text": text,
        "structured": {
            "summary": summary,
            "bills": [r.model_dump(mode="json") for r in returned],
            "bills_truncated": len(sorted_records) > _MAX_RESULTS,
            "query": {"age": max(ages) if ages else None, "page_no": 1, "num_of_rows": _MAX_RESULTS},
        },
        "source_url": None,
        "metadata": {
            "fetched_at": cached_at.isoformat(),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": "search_bill_info",
            "cache_used": True,
        },
    }


def _format_public_job(content: str, cached_at: datetime) -> dict[str, Any]:
    records = normalize_public_job(content)
    ongoing_count = sum(1 for r in records if r.is_ongoing)
    institutes = sorted({r.institute for r in records if r.institute})
    begin_dates = [r.pbanc_begin_date for r in records if r.pbanc_begin_date]
    summary = {
        "count": len(records),
        "ongoing_count": ongoing_count,
        "institutes": institutes,
        "latest_pbanc_begin_date": max(begin_dates).isoformat() if begin_dates else None,
    }
    sorted_records = sorted(records, key=lambda r: r.pbanc_begin_date or date.min, reverse=True)
    returned = sorted_records[:_MAX_RESULTS]

    count = summary["count"]
    text = (
        f"공공기관 채용공시 {count}건 (진행 {ongoing_count}건, 캐시). "
        f"최신 {summary['latest_pbanc_begin_date'] or '-'}."
        if count > 0 else "공공기관 채용공시 0건 (캐시)."
    )
    return {
        "text": text,
        "structured": {
            "summary": summary,
            "postings": [r.model_dump(mode="json") for r in returned],
            "postings_truncated": len(sorted_records) > _MAX_RESULTS,
            "query": {
                "page_no": 1,
                "num_of_rows": _MAX_RESULTS,
                "ongoing_yn": None,
                "recrut_pbanc_ttl": None,
            },
        },
        "source_url": None,
        "metadata": {
            "fetched_at": cached_at.isoformat(),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": "search_public_job",
            "cache_used": True,
        },
    }


def _format_worknet_job(content: str, cached_at: datetime) -> dict[str, Any]:
    try:
        records = normalize_worknet_job(content)
    except WorknetPermissionDeniedError:
        return {
            "text": "워크넷 채용공고: 사업자/기관 회원 권한 필요 (캐시된 권한 거부 응답).",
            "structured": {
                "summary": {"count": 0},
                "postings": [],
                "postings_truncated": False,
            },
            "source_url": None,
            "metadata": {
                "fetched_at": cached_at.isoformat(),
                "raw_count": 0,
                "returned_count": 0,
                "tool_name": "search_worknet_job",
                "cache_used": True,
                "api_status": "permission_denied",
            },
        }

    reg_dates = [r.reg_date for r in records if r.reg_date]
    emp_types = sorted({r.emp_type for r in records if r.emp_type})
    summary = {
        "count": len(records),
        "latest_reg_date": max(reg_dates).isoformat() if reg_dates else None,
        "emp_types": emp_types,
    }
    sorted_records = sorted(records, key=lambda r: r.reg_date or date.min, reverse=True)
    returned = sorted_records[:_MAX_RESULTS]

    count = summary["count"]
    text = (
        f"워크넷 채용공고 {count}건 (캐시). 최신 등록 {summary['latest_reg_date'] or '-'}."
        if count > 0 else "워크넷 채용공고 0건 (캐시)."
    )
    return {
        "text": text,
        "structured": {
            "summary": summary,
            "postings": [r.model_dump(mode="json") for r in returned],
            "postings_truncated": len(sorted_records) > _MAX_RESULTS,
            "query": {"page_no": 1, "num_of_rows": _MAX_RESULTS},
        },
        "source_url": None,
        "metadata": {
            "fetched_at": cached_at.isoformat(),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": "search_worknet_job",
            "cache_used": True,
        },
    }


def _to_eok(amount_won: int | None) -> str:
    if amount_won is None:
        return "-"
    if amount_won >= 100_000_000:
        return f"{amount_won / 100_000_000:.1f}억"
    if amount_won >= 10_000:
        return f"{amount_won / 10_000:,.0f}만"
    return f"{amount_won:,}원"


def _format_g2b_bid(content: str, cached_at: datetime) -> dict[str, Any]:
    records = normalize_g2b_bid(content)
    estimated = [r.estimated_price for r in records if r.estimated_price]
    budgets = [r.assigned_budget for r in records if r.assigned_budget]
    summary = {
        "count": len(records),
        "avg_estimated_price": round(sum(estimated) / len(estimated)) if estimated else None,
        "min_estimated_price": min(estimated) if estimated else None,
        "max_estimated_price": max(estimated) if estimated else None,
        "total_assigned_budget": sum(budgets) if budgets else None,
    }
    sorted_records = sorted(records, key=lambda r: r.notice_date, reverse=True)
    returned = sorted_records[:_MAX_RESULTS]

    count = summary["count"]
    text = (
        f"나라장터 입찰공고 {count}건 (캐시). "
        f"평균 추정가 {_to_eok(summary['avg_estimated_price'])}, "
        f"최고 추정가 {_to_eok(summary['max_estimated_price'])}."
        if count > 0 else "나라장터 입찰공고 0건 (캐시)."
    )
    return {
        "text": text,
        "structured": {
            "summary": summary,
            "notices": [r.model_dump(mode="json") for r in returned],
            "notices_truncated": len(sorted_records) > _MAX_RESULTS,
            "query": {
                "inqry_bgn_dt": None,
                "inqry_end_dt": None,
                "page_no": 1,
                "num_of_rows": _MAX_RESULTS,
            },
        },
        "source_url": None,
        "metadata": {
            "fetched_at": cached_at.isoformat(),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": "search_g2b_bid",
            "cache_used": True,
        },
    }


_FORMATTER_REGISTRY: dict[str, Callable[[str, datetime], dict[str, Any]]] = {
    "search_law_info": _format_law_info,
    "search_bill_info": _format_bill_info,
    "search_public_job": _format_public_job,
    "search_worknet_job": _format_worknet_job,
    "search_g2b_bid": _format_g2b_bid,
}

__all__ = ["check_api_cache", "get_cached_data"]
