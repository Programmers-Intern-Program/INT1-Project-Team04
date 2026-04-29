"""법률 도메인 MCP 도구.

등록 도구 (2종):
- search_law_info  — 법제처 국가법령정보 공유서비스 (data.go.kr/data/15000115)
- search_bill_info — 국회사무처 의안정보 통합 API (data.go.kr/data/15126134)

원칙:
- serviceKey/KEY 같은 비밀값은 도구 함수 인자로 받지 않는다 (Langfuse 입력 캡처 보호).
- 외부 호출은 sources.api_source_service.fetch() 만 경유.
- 토큰 절약을 위해 상위 _MAX_RESULTS 건만 반환 (정렬 키는 도구별로 다름).
"""

from datetime import date, datetime
from typing import Any
from urllib.parse import urlencode

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.law.errors import LawConfigError
from mcp_server.domains.law.normalizer import (
    BillItem,
    LawItem,
    normalize_bill_info,
    normalize_law_info,
)
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service

_DEFAULT_NUM_OF_ROWS = 20
_MAX_RESULTS = 20

# tool_name → api_source.id 캐시
_cached_source_ids: dict[str, int] = {}


async def _resolve_source_id(tool_name: str) -> int:
    if tool_name not in _cached_source_ids:
        _cached_source_ids[tool_name] = (
            await api_source_service.resolve_source_id_by_tool_name(tool_name)
        )
    return _cached_source_ids[tool_name]


def _mask_query_string(params: dict[str, Any], *, secret_keys: tuple[str, ...]) -> str:
    """source_url 노출용 query string. 비밀 키는 '***' 로 마스킹."""
    masked = {k: ("***" if k in secret_keys else v) for k, v in params.items()}
    return urlencode(masked, safe="*")


def _format_date(d: date | None) -> str:
    return d.isoformat() if d else "-"


# ─────────────────────────────────────────────
# 도구 1: 법제처 국가법령정보
# ─────────────────────────────────────────────

_TOOL_LAW_INFO = "search_law_info"


class SearchLawInfoInput(BaseModel):
    """현행법령 목록조회 입력 (법제처 명세 기준)."""

    query: str = Field(
        default="*",
        description="검색어 (법령명 키워드). 기본 '*' (전체).",
        min_length=1,
        max_length=200,
    )
    page_no: int = Field(
        default=1,
        ge=1,
        description="페이지 번호 (1부터).",
    )
    num_of_rows: int = Field(
        default=_DEFAULT_NUM_OF_ROWS,
        ge=1,
        le=9999,
        description="페이지당 결과 수.",
    )


def _summarize_law(records: list[LawItem]) -> dict[str, Any]:
    if not records:
        return {"count": 0, "latest_promulgation_date": None, "ministries": []}
    promulgation_dates = [r.promulgation_date for r in records if r.promulgation_date]
    ministries = sorted({r.ministry_name for r in records if r.ministry_name})
    return {
        "count": len(records),
        "latest_promulgation_date": (
            max(promulgation_dates).isoformat() if promulgation_dates else None
        ),
        "ministries": ministries,
    }


def _law_text(query: str, summary: dict[str, Any], records: list[LawItem]) -> str:
    if summary["count"] == 0:
        return f"현행법령 검색 '{query}' 0건."
    latest_date = summary["latest_promulgation_date"] or "-"
    latest_record = max(
        (r for r in records if r.promulgation_date),
        key=lambda r: r.promulgation_date,
        default=None,
    )
    latest_name = latest_record.law_name if latest_record else "-"
    return (
        f"현행법령 검색 '{query}' {summary['count']}건. "
        f"최신 공포 {latest_date} ({latest_name})."
    )


@mcp.tool()
@traced(_TOOL_LAW_INFO)
async def search_law_info(input: SearchLawInfoInput) -> dict[str, Any]:
    """법제처 **국가법령정보 공유서비스 — 현행법령 목록조회**.

    검색어로 현행 법령 목록(target=law)을 조회한다. 응답은 정규화된 LawItem 리스트와
    {text, structured, source_url, metadata} 공통 스키마로 반환된다.

    반환 dict:
      - text: 통계 요약 한 단락 (건수 + 최신 공포일 및 법령명).
      - structured.summary: {count, latest_promulgation_date, ministries[]}.
      - structured.laws: LawItem dict 의 상위 20건 (공포일자 내림차순).
      - structured.laws_truncated: 원본이 20건 초과로 절단됐으면 True.
      - structured.query: 호출 인자 echo.
      - source_url: 호출한 API URL + query (serviceKey '***' 마스킹).
      - metadata: {fetched_at, raw_count, returned_count, tool_name, source_id}.

    Raises:
        LawConfigError: MOLEG_LAW_INFO_API_KEY 미설정.
        LawNormalizationError: API 응답이 비정상(코드 != "00") 또는 XML 파싱 실패.
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.moleg_law_info_api_key:
        raise LawConfigError(
            "MOLEG_LAW_INFO_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await _resolve_source_id(_TOOL_LAW_INFO)
    params: dict[str, Any] = {
        "serviceKey": settings.moleg_law_info_api_key,
        "target": "law",
        "query": input.query,
        "numOfRows": input.num_of_rows,
        "pageNo": input.page_no,
    }
    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[LawItem] = normalize_law_info(raw.content)
    summary = _summarize_law(records)

    sorted_records = sorted(
        records,
        key=lambda r: r.promulgation_date or date.min,
        reverse=True,
    )
    truncated = len(sorted_records) > _MAX_RESULTS
    returned = sorted_records[:_MAX_RESULTS]

    url_template = raw.raw_metadata.get("url_template", "")
    source_url = (
        f"{url_template}?{_mask_query_string(params, secret_keys=('serviceKey',))}"
        if url_template
        else None
    )

    return {
        "text": _law_text(input.query, summary, records),
        "structured": {
            "summary": summary,
            "laws": [r.model_dump(mode="json") for r in returned],
            "laws_truncated": truncated,
            "query": {
                "query": input.query,
                "page_no": input.page_no,
                "num_of_rows": input.num_of_rows,
            },
        },
        "source_url": source_url,
        "metadata": {
            "fetched_at": (
                raw.fetched_at.isoformat()
                if isinstance(raw.fetched_at, datetime)
                else str(raw.fetched_at)
            ),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": _TOOL_LAW_INFO,
            "source_id": source_id,
        },
    }


# ─────────────────────────────────────────────
# 도구 2: 국회 의안정보
# ─────────────────────────────────────────────

_TOOL_BILL_INFO = "search_bill_info"


class SearchBillInfoInput(BaseModel):
    """의안 검색 입력."""

    age: int = Field(
        default=22,
        ge=1,
        le=22,
        description="국회 대수 (1~22). 기본 22 (현 국회).",
    )
    page_no: int = Field(default=1, ge=1, description="페이지 번호 (1부터).")
    num_of_rows: int = Field(
        default=_DEFAULT_NUM_OF_ROWS,
        ge=1,
        le=100,
        description="페이지당 결과 수 (1~100).",
    )


def _summarize_bill(records: list[BillItem]) -> dict[str, Any]:
    if not records:
        return {"count": 0, "latest_propose_date": None, "committees": []}
    propose_dates = [r.propose_date for r in records if r.propose_date]
    committees = sorted({r.committee for r in records if r.committee})
    return {
        "count": len(records),
        "latest_propose_date": (
            max(propose_dates).isoformat() if propose_dates else None
        ),
        "committees": committees,
    }


def _bill_text(age: int, summary: dict[str, Any], records: list[BillItem]) -> str:
    if summary["count"] == 0:
        return f"제{age}대 의안 검색 0건."
    latest_date = summary["latest_propose_date"] or "-"
    latest_record = max(
        (r for r in records if r.propose_date),
        key=lambda r: r.propose_date,
        default=None,
    )
    latest_name = latest_record.bill_name if latest_record else "-"
    return (
        f"제{age}대 의안 검색 {summary['count']}건. "
        f"최근 발의 {latest_date} ({latest_name})."
    )


@mcp.tool()
@traced(_TOOL_BILL_INFO)
async def search_bill_info(input: SearchBillInfoInput) -> dict[str, Any]:
    """국회사무처 **의안정보** 조회 (열린국회정보 기반).

    지정 대수의 의안 목록을 조회하고 정규화된 BillItem 리스트와
    {text, structured, source_url, metadata} 공통 스키마로 반환한다.

    반환 dict:
      - text: 통계 요약 (건수 + 최근 발의일 및 의안명).
      - structured.summary: {count, latest_propose_date, committees[]}.
      - structured.bills: BillItem dict 의 상위 20건 (발의일자 내림차순).
      - structured.bills_truncated / query / source_url / metadata: 동일.

    Raises:
        LawConfigError: NA_BILL_INFO_API_KEY 미설정.
        LawNormalizationError: API 응답이 비정상(코드 != "INFO-000") 또는 XML 파싱 실패.
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.na_bill_info_api_key:
        raise LawConfigError(
            "NA_BILL_INFO_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await _resolve_source_id(_TOOL_BILL_INFO)
    params: dict[str, Any] = {
        "KEY": settings.na_bill_info_api_key,
        "Type": "xml",
        "pIndex": input.page_no,
        "pSize": input.num_of_rows,
        "AGE": input.age,
    }
    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[BillItem] = normalize_bill_info(raw.content)
    summary = _summarize_bill(records)

    sorted_records = sorted(
        records,
        key=lambda r: r.propose_date or date.min,
        reverse=True,
    )
    truncated = len(sorted_records) > _MAX_RESULTS
    returned = sorted_records[:_MAX_RESULTS]

    url_template = raw.raw_metadata.get("url_template", "")
    source_url = (
        f"{url_template}?{_mask_query_string(params, secret_keys=('KEY',))}"
        if url_template
        else None
    )

    return {
        "text": _bill_text(input.age, summary, records),
        "structured": {
            "summary": summary,
            "bills": [r.model_dump(mode="json") for r in returned],
            "bills_truncated": truncated,
            "query": {
                "age": input.age,
                "page_no": input.page_no,
                "num_of_rows": input.num_of_rows,
            },
        },
        "source_url": source_url,
        "metadata": {
            "fetched_at": (
                raw.fetched_at.isoformat()
                if isinstance(raw.fetched_at, datetime)
                else str(raw.fetched_at)
            ),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": _TOOL_BILL_INFO,
            "source_id": source_id,
        },
    }


__all__ = [
    "SearchBillInfoInput",
    "SearchLawInfoInput",
    "search_bill_info",
    "search_law_info",
]
