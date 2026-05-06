"""캐시 상태 확인 및 캐시 직접 읽기 MCP 도구.

등록 도구 (2종):
- check_api_cache  — staleness 센서. fetch tool 호출 전 캐시 상태 확인.
- get_cached_data  — 캐시에서 직접 데이터 읽기. 외부 API 미호출.

캐시 키 전략:
- bulk-fetch tool (법률·채용·경매): tool_name 단위 1행.
- param-keyed tool (부동산·의안):  tool_name + 핵심 params 조합당 1행.
"""

from datetime import date, datetime
from typing import Any, Callable

from mcp_server.domains.auction.normalizer import normalize_g2b_bid
from mcp_server.domains.jobs.errors import WorknetPermissionDeniedError
from mcp_server.domains.jobs.normalizer import normalize_public_job, normalize_worknet_job
from mcp_server.domains.law.normalizer import normalize_bill_info, normalize_law_info
from mcp_server.domains.real_estate.normalizer import (
    normalize_apt_rent,
    normalize_apt_trade,
    normalize_offi_rent,
    normalize_offi_trade,
    normalize_rh_rent,
    normalize_rh_trade,
)
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service
from mcp_server.sources.api_source_service import _PARAM_KEY_FIELDS

_MAX_RESULTS = 20


# ─────────────────────────────────────────────
# 도구 1: check_api_cache
# ─────────────────────────────────────────────


@mcp.tool()
@traced("check_api_cache")
async def check_api_cache(tool_name: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    """fetch tool 호출 전 반드시 이 tool을 먼저 호출해 캐시 상태를 확인하라.

    반환된 cache_hit + last_fetched_at + tool_name(도메인 맥락)으로 fetch 필요 여부를 판단.
    - cache_hit: false  → search_* fetch tool 호출 필요
    - cache_hit: true, 신선 → get_cached_data(tool_name, params) 호출 (외부 API 미호출)
    - cache_hit: true, stale → search_* fetch tool 호출 필요

    freshness 기준은 도메인 특성에 따라 직접 판단할 것.
    법률=주 단위, 채용=일 단위, 경매=시간 단위, 부동산·의안=월/대수 단위 등.

    **param-keyed tool (부동산·의안)은 params 필수:**
    - 부동산 6종: {"lawd_cd": "11680", "deal_ymd": "202403"}
    - search_bill_info: {"age": 22}
    params 없이 호출하면 cache_hit: false + 안내 메시지 반환.

    Args:
        tool_name: 예: "search_law_info", "search_house_price".
        params: param-keyed tool 전용. 부동산은 lawd_cd+deal_ymd, 의안은 age 필요.
    """
    if tool_name in _PARAM_KEY_FIELDS and not params:
        required = list(_PARAM_KEY_FIELDS[tool_name])
        return {
            "cache_hit": False,
            "last_fetched_at": None,
            "tool_name": tool_name,
            "message": f"{tool_name}은 params 필수: {required} (소문자 허용)",
        }

    cached_at: datetime | None = await api_source_service.peek_cached_at(tool_name, params)

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
async def get_cached_data(tool_name: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    """check_api_cache 결과가 cache_hit: true이고 신선하다고 판단할 때만 호출.

    캐시에서 데이터를 직접 읽어 정규화된 결과를 반환한다. 외부 API를 호출하지 않는다.
    응답 포맷은 fetch tool과 동일 (text, structured, source_url, metadata).
    metadata.cache_used: true 로 캐시 응답임을 표시.

    **param-keyed tool (부동산·의안)은 params 필수:**
    - 부동산 6종: {"lawd_cd": "11680", "deal_ymd": "202403"}
    - search_bill_info: {"age": 22}

    Args:
        tool_name: 예: "search_law_info", "search_house_price".
        params: param-keyed tool 전용.
    """
    if tool_name in _PARAM_KEY_FIELDS and not params:
        required = list(_PARAM_KEY_FIELDS[tool_name])
        return {
            "cache_hit": False,
            "tool_name": tool_name,
            "message": f"{tool_name}은 params 필수: {required} (소문자 허용)",
        }

    result = await api_source_service.peek_cached_content(tool_name, params)
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

    return formatter(content, cached_at, params or {})


# ─────────────────────────────────────────────
# bulk-fetch formatter (params 불필요, 시그니처 통일용 _ 무시)
# ─────────────────────────────────────────────


def _format_law_info(content: str, cached_at: datetime, _params: dict) -> dict[str, Any]:
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


def _format_bill_info(content: str, cached_at: datetime, params: dict) -> dict[str, Any]:
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

    # params에서 AGE 복원 (대소문자 무관)
    age = int((params.get("AGE") or params.get("age") or (max(ages) if ages else 22)))
    age_str = f"제{age}대 " if age else ""
    count = summary["count"]
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
            "query": {"age": age, "page_no": 1, "num_of_rows": _MAX_RESULTS},
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


def _format_public_job(content: str, cached_at: datetime, params: dict) -> dict[str, Any]:
    records = normalize_public_job(content)
    filtered = records
    # 채용 캐시는 bulk 원천 데이터라 구독 조건 필터를 formatter에서 재적용한다.
    ongoing_yn = _optional_text(params.get("ongoing_yn") or params.get("ongoingYn"))
    keyword = _optional_text(params.get("recrut_pbanc_ttl") or params.get("recrutPbancTtl"))
    if ongoing_yn == "Y":
        filtered = [r for r in filtered if r.is_ongoing]
    if keyword:
        filtered = [r for r in filtered if keyword in (r.title or "")]

    ongoing_count = sum(1 for r in filtered if r.is_ongoing)
    institutes = sorted({r.institute for r in filtered if r.institute})
    begin_dates = [r.pbanc_begin_date for r in filtered if r.pbanc_begin_date]
    summary = {
        "count": len(filtered),
        "ongoing_count": ongoing_count,
        "institutes": institutes,
        "latest_pbanc_begin_date": max(begin_dates).isoformat() if begin_dates else None,
    }
    sorted_records = sorted(filtered, key=lambda r: r.pbanc_begin_date or date.min, reverse=True)
    page_no = _positive_int(params.get("page_no") or params.get("pageNo"), 1)
    num_of_rows = _positive_int(params.get("num_of_rows") or params.get("numOfRows"), _MAX_RESULTS)
    returned = _page_records(sorted_records, page_no, num_of_rows)

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
                "page_no": page_no,
                "num_of_rows": num_of_rows,
                "ongoing_yn": ongoing_yn,
                "recrut_pbanc_ttl": keyword,
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


def _format_worknet_job(content: str, cached_at: datetime, params: dict) -> dict[str, Any]:
    try:
        records = normalize_worknet_job(content)
    except WorknetPermissionDeniedError:
        # 권한 거부 캐시는 Worknet source만 제외할 수 있도록 structured에도 표시한다.
        return {
            "text": "워크넷 채용공고: 사업자/기관 회원 권한 필요 (캐시된 권한 거부 응답).",
            "structured": {
                "summary": {"count": 0},
                "postings": [],
                "postings_truncated": False,
                "permission_denied": True,
                "query": {
                    "keyword": _optional_text(params.get("keyword")),
                    "page_no": _positive_int(params.get("start_page") or params.get("page_no"), 1),
                    "display": _positive_int(params.get("display"), _MAX_RESULTS),
                },
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

    filtered = records
    # Worknet도 API 호출은 wide fetch이고, 구독 keyword는 캐시 읽기 단계에서 적용한다.
    keyword = _optional_text(params.get("keyword"))
    if keyword:
        filtered = [
            r for r in filtered
            if keyword in (r.title or "") or keyword in (r.company or "")
        ]

    reg_dates = [r.reg_date for r in filtered if r.reg_date]
    emp_types = sorted({r.emp_type for r in filtered if r.emp_type})
    summary = {
        "count": len(filtered),
        "latest_reg_date": max(reg_dates).isoformat() if reg_dates else None,
        "emp_types": emp_types,
    }
    sorted_records = sorted(filtered, key=lambda r: r.reg_date or date.min, reverse=True)
    start_page = _positive_int(params.get("start_page") or params.get("page_no"), 1)
    display = _positive_int(params.get("display"), _MAX_RESULTS)
    returned = _page_records(sorted_records, start_page, display)

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
            "permission_denied": False,
            "query": {"keyword": keyword, "page_no": start_page, "display": display},
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


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _positive_int(value: Any, default: int) -> int:
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _page_records(records: list[Any], page_no: int, page_size: int) -> list[Any]:
    start = (page_no - 1) * page_size
    end = start + page_size
    return records[start:end][:_MAX_RESULTS]


def _to_eok(amount_won: int | None) -> str:
    if amount_won is None:
        return "-"
    if amount_won >= 100_000_000:
        return f"{amount_won / 100_000_000:.1f}억"
    if amount_won >= 10_000:
        return f"{amount_won / 10_000:,.0f}만"
    return f"{amount_won:,}원"


def _format_g2b_bid(content: str, cached_at: datetime, _params: dict) -> dict[str, Any]:
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
            "query": {"inqry_bgn_dt": None, "inqry_end_dt": None, "page_no": 1, "num_of_rows": _MAX_RESULTS},
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


# ─────────────────────────────────────────────
# param-keyed formatter — 부동산 (factory)
# ─────────────────────────────────────────────


def _make_real_estate_formatter(
    normalizer: Callable,
    tool_name: str,
    kind: str,
    is_trade: bool,
) -> Callable[[str, datetime, dict], dict[str, Any]]:
    """부동산 6종 formatter 공통 factory.

    is_trade=True  → deal_amount 기준 통계 (매매)
    is_trade=False → deposit 기준 통계 (전월세)
    """
    def formatter(content: str, cached_at: datetime, params: dict) -> dict[str, Any]:
        normalized_params = {k.upper(): str(v) for k, v in params.items()}
        lawd_cd = normalized_params.get("LAWD_CD", "")
        deal_ymd = normalized_params.get("DEAL_YMD", "")

        records = normalizer(content, lawd_cd=lawd_cd)

        if is_trade:
            amounts = [r.deal_amount for r in records]
            summary = {
                "count": len(records),
                "avg_deal_amount": round(sum(amounts) / len(amounts)) if amounts else None,
                "min_deal_amount": min(amounts) if amounts else None,
                "max_deal_amount": max(amounts) if amounts else None,
            }
            sorted_records = sorted(records, key=lambda r: r.deal_amount, reverse=True)
            text = (
                f"LAWD_CD {lawd_cd} {deal_ymd} {kind} 실거래 {len(records)}건 (캐시). "
                f"평균 {_to_eok(summary['avg_deal_amount'])}."
                if records else f"LAWD_CD {lawd_cd} {deal_ymd} {kind} 실거래 0건 (캐시)."
            )
        else:
            deposits = [r.deposit for r in records]
            monthly = [r.monthly_rent for r in records]
            summary = {
                "count": len(records),
                "avg_deposit": round(sum(deposits) / len(deposits)) if deposits else None,
                "min_deposit": min(deposits) if deposits else None,
                "max_deposit": max(deposits) if deposits else None,
                "avg_monthly_rent": round(sum(monthly) / len(monthly)) if monthly else None,
            }
            sorted_records = sorted(records, key=lambda r: r.deposit, reverse=True)
            text = (
                f"LAWD_CD {lawd_cd} {deal_ymd} {kind} 실거래 {len(records)}건 (캐시). "
                f"평균 보증금 {_to_eok(summary['avg_deposit'])}."
                if records else f"LAWD_CD {lawd_cd} {deal_ymd} {kind} 실거래 0건 (캐시)."
            )

        returned = sorted_records[:_MAX_RESULTS]
        return {
            "text": text,
            "structured": {
                "summary": summary,
                "trades": [r.model_dump(mode="json") for r in returned],
                "trades_truncated": len(sorted_records) > _MAX_RESULTS,
                "query": {"lawd_cd": lawd_cd, "deal_ymd": deal_ymd},
            },
            "source_url": None,
            "metadata": {
                "fetched_at": cached_at.isoformat(),
                "raw_count": len(records),
                "returned_count": len(returned),
                "tool_name": tool_name,
                "cache_used": True,
            },
        }

    return formatter


# ─────────────────────────────────────────────
# 레지스트리
# ─────────────────────────────────────────────

_FORMATTER_REGISTRY: dict[str, Callable[[str, datetime, dict], dict[str, Any]]] = {
    # bulk-fetch
    "search_law_info":    _format_law_info,
    "search_bill_info":   _format_bill_info,
    "search_public_job":  _format_public_job,
    "search_worknet_job": _format_worknet_job,
    "search_g2b_bid":     _format_g2b_bid,
    # param-keyed (부동산)
    "search_house_price": _make_real_estate_formatter(normalize_apt_trade,  "search_house_price", "아파트 매매",      is_trade=True),
    "search_apt_rent":    _make_real_estate_formatter(normalize_apt_rent,   "search_apt_rent",    "아파트 전월세",     is_trade=False),
    "search_offi_trade":  _make_real_estate_formatter(normalize_offi_trade, "search_offi_trade",  "오피스텔 매매",     is_trade=True),
    "search_offi_rent":   _make_real_estate_formatter(normalize_offi_rent,  "search_offi_rent",   "오피스텔 전월세",   is_trade=False),
    "search_rh_rent":     _make_real_estate_formatter(normalize_rh_rent,    "search_rh_rent",     "연립다세대 전월세", is_trade=False),
    "search_rh_trade":    _make_real_estate_formatter(normalize_rh_trade,   "search_rh_trade",    "연립다세대 매매",   is_trade=True),
}

__all__ = ["check_api_cache", "get_cached_data"]
