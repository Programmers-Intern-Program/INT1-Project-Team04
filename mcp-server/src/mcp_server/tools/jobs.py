"""채용 도메인 MCP 도구.

등록 도구 (2종):
- search_public_job  — 기획재정부 공공기관 채용정보 (data.go.kr/data/15125273)
- search_worknet_job — 한국고용정보원 워크넷 채용정보 (data.go.kr/data/3038225)

원칙:
- serviceKey/authKey 같은 비밀값은 도구 인자로 받지 않는다 (Langfuse 입력 캡처 보호).
- 외부 호출은 sources.api_source_service.fetch() 만 경유.
- 토큰 절약을 위해 상위 _MAX_RESULTS 건만 반환.

워크넷 권한 거부 처리:
  현재 발급된 키가 개인회원 키인 경우 외부 API 가 <error> 응답을 반환한다.
  이는 키 교체로 해결되지 않으며 사업자/기관 회원 발급이 필요하다.
  도구는 WorknetPermissionDeniedError 를 잡아 정상 4필드 스키마 응답으로 변환하되,
  metadata.api_status="permission_denied" 플래그와 text 안내 메시지를 포함시켜
  호출자(LLM/백엔드) 가 분기할 수 있게 한다.
"""

from datetime import date, datetime
from typing import Any
from urllib.parse import urlencode

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.jobs.errors import (
    JobsConfigError,
    WorknetPermissionDeniedError,
)
from mcp_server.domains.jobs.normalizer import (
    PublicJobPosting,
    WorknetJobPosting,
    normalize_public_job,
    normalize_worknet_job,
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
    masked = {k: ("***" if k in secret_keys else v) for k, v in params.items()}
    return urlencode(masked, safe="*")


def _build_source_url(raw, params: dict[str, Any], secret_keys: tuple[str, ...]) -> str | None:
    url_template = raw.raw_metadata.get("url_template", "")
    if not url_template:
        return None
    return f"{url_template}?{_mask_query_string(params, secret_keys=secret_keys)}"


def _fetched_at(raw) -> str:
    return (
        raw.fetched_at.isoformat()
        if isinstance(raw.fetched_at, datetime)
        else str(raw.fetched_at)
    )


# ─────────────────────────────────────────────
# 도구 1: 기획재정부 공공기관 채용 (ALIO)
# ─────────────────────────────────────────────

_TOOL_PUBLIC_JOB = "search_public_job"


class SearchPublicJobInput(BaseModel):
    """공공기관 채용공시 목록조회 입력."""

    page_no: int = Field(default=1, ge=1, description="페이지 번호 (1부터).")
    num_of_rows: int = Field(
        default=10,
        ge=1,
        le=100,
        description="페이지당 결과 수 (1~100). 공식 기본값 10.",
    )
    ongoing_yn: str | None = Field(
        default=None,
        pattern=r"^[YN]$",
        description="진행 중 공고만 (Y) / 마감 포함 (N). 미지정 시 서버 기본.",
    )
    recrut_pbanc_ttl: str | None = Field(
        default=None,
        description="공시제목 부분일치 검색어 (예: '데이터').",
    )


def _summarize_public_job(records: list[PublicJobPosting]) -> dict[str, Any]:
    if not records:
        return {
            "count": 0,
            "ongoing_count": 0,
            "institutes": [],
            "latest_pbanc_begin_date": None,
        }
    ongoing_count = sum(1 for r in records if r.is_ongoing)
    institutes = sorted({r.institute for r in records if r.institute})
    begin_dates = [r.pbanc_begin_date for r in records if r.pbanc_begin_date]
    return {
        "count": len(records),
        "ongoing_count": ongoing_count,
        "institutes": institutes,
        "latest_pbanc_begin_date": (
            max(begin_dates).isoformat() if begin_dates else None
        ),
    }


def _public_job_text(summary: dict[str, Any], records: list[PublicJobPosting]) -> str:
    if summary["count"] == 0:
        return "공공기관 채용공시 0건."
    latest_record = max(
        (r for r in records if r.pbanc_begin_date),
        key=lambda r: r.pbanc_begin_date,
        default=None,
    )
    latest_name = latest_record.title if latest_record else "-"
    latest_date = summary["latest_pbanc_begin_date"] or "-"
    return (
        f"공공기관 채용공시 {summary['count']}건 "
        f"(진행 {summary['ongoing_count']}건). "
        f"최신 공고 {latest_date} ({latest_name})."
    )


@mcp.tool()
@traced(_TOOL_PUBLIC_JOB)
async def search_public_job(input: SearchPublicJobInput) -> dict[str, Any]:
    """기획재정부 **공공기관 채용공시 목록조회** (ALIO 기반).

    공공기관 경영정보 공개시스템 채용공시 목록을 조회하고 정규화된
    PublicJobPosting 리스트와 {text, structured, source_url, metadata} 공통
    스키마로 반환한다.

    반환 dict:
      - text: 통계 요약 (총건수 + 진행건수 + 최신 공고).
      - structured.summary: {count, ongoing_count, institutes[], latest_pbanc_begin_date}.
      - structured.postings: PublicJobPosting dict 의 상위 20건 (공고시작일 내림차순).
      - structured.postings_truncated / query / source_url / metadata: 동일.

    Raises:
        JobsConfigError: MOEF_PUBLIC_JOB_API_KEY 미설정.
        JobsNormalizationError: API 응답이 비정상(코드 != 200) 또는 JSON 파싱 실패.
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.moef_public_job_api_key:
        raise JobsConfigError(
            "MOEF_PUBLIC_JOB_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await _resolve_source_id(_TOOL_PUBLIC_JOB)
    params: dict[str, Any] = {
        "serviceKey": settings.moef_public_job_api_key,
        "pageNo": input.page_no,
        "numOfRows": input.num_of_rows,
        "resultType": "json",
    }
    if input.ongoing_yn is not None:
        params["ongoingYn"] = input.ongoing_yn
    if input.recrut_pbanc_ttl:
        params["recrutPbancTtl"] = input.recrut_pbanc_ttl

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[PublicJobPosting] = normalize_public_job(raw.content)
    summary = _summarize_public_job(records)

    sorted_records = sorted(
        records,
        key=lambda r: r.pbanc_begin_date or date.min,
        reverse=True,
    )
    truncated = len(sorted_records) > _MAX_RESULTS
    returned = sorted_records[:_MAX_RESULTS]

    return {
        "text": _public_job_text(summary, records),
        "structured": {
            "summary": summary,
            "postings": [r.model_dump(mode="json") for r in returned],
            "postings_truncated": truncated,
            "query": {
                "page_no": input.page_no,
                "num_of_rows": input.num_of_rows,
                "ongoing_yn": input.ongoing_yn,
                "recrut_pbanc_ttl": input.recrut_pbanc_ttl,
            },
        },
        "source_url": _build_source_url(raw, params, secret_keys=("serviceKey",)),
        "metadata": {
            "fetched_at": _fetched_at(raw),
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": _TOOL_PUBLIC_JOB,
            "source_id": source_id,
        },
    }


# ─────────────────────────────────────────────
# 도구 2: 워크넷 채용정보 (KEIS) — 권한 거부 분기 포함
# ─────────────────────────────────────────────

_TOOL_WORKNET_JOB = "search_worknet_job"


class SearchWorknetJobInput(BaseModel):
    """워크넷 채용 검색 입력."""

    keyword: str = Field(
        default="",
        description="검색어 (직무/회사명 등). 빈 문자열이면 최신 공고 페이지.",
    )
    start_page: int = Field(default=1, ge=1, description="시작 페이지 (1부터).")
    display: int = Field(
        default=_DEFAULT_NUM_OF_ROWS,
        ge=1,
        le=100,
        description="페이지당 결과 수 (1~100).",
    )


def _summarize_worknet(records: list[WorknetJobPosting]) -> dict[str, Any]:
    if not records:
        return {"count": 0, "regions": [], "latest_reg_date": None}
    regions = sorted({r.region for r in records if r.region})
    reg_dates = [r.reg_date for r in records if r.reg_date]
    return {
        "count": len(records),
        "regions": regions,
        "latest_reg_date": max(reg_dates).isoformat() if reg_dates else None,
    }


def _worknet_text(keyword: str, summary: dict[str, Any]) -> str:
    keyword_label = keyword or "(전체)"
    if summary["count"] == 0:
        return f"워크넷 채용 검색 '{keyword_label}' 0건."
    return (
        f"워크넷 채용 검색 '{keyword_label}' {summary['count']}건. "
        f"최근 등록 {summary['latest_reg_date'] or '-'}."
    )


def _build_permission_denied_response(
    *,
    message: str,
    keyword: str,
    params: dict[str, Any],
    source_url: str | None,
    fetched_at_iso: str,
    source_id: int,
) -> dict[str, Any]:
    """워크넷 권한 거부 응답 → 정상 4필드 스키마 + permission_denied 플래그.

    호출자(LLM/백엔드) 가 metadata.api_status 로 분기할 수 있도록 일반 0건 응답과
    구분된 형태로 반환한다.
    """
    return {
        "text": (
            f"워크넷 OPEN-API 권한 미발급 — 사업자/기관 회원 키가 필요합니다 "
            f"(개인회원 키로 호출됨). 외부 API 응답: '{message}'"
        ),
        "structured": {
            "summary": {"count": 0, "regions": [], "latest_reg_date": None},
            "postings": [],
            "postings_truncated": False,
            "permission_denied": True,
            "permission_denied_message": message,
            "query": {
                "keyword": keyword,
                "page_no": params.get("startPage"),
                "display": params.get("display"),
            },
        },
        "source_url": source_url,
        "metadata": {
            "fetched_at": fetched_at_iso,
            "raw_count": 0,
            "returned_count": 0,
            "tool_name": _TOOL_WORKNET_JOB,
            "source_id": source_id,
            "api_status": "permission_denied",
        },
    }


@mcp.tool()
@traced(_TOOL_WORKNET_JOB)
async def search_worknet_job(input: SearchWorknetJobInput) -> dict[str, Any]:
    """**워크넷 채용정보** 조회 (한국고용정보원).

    키워드로 채용 공고 목록을 조회하고 정규화된 WorknetJobPosting 리스트와
    {text, structured, source_url, metadata} 공통 스키마로 반환한다.

    참고 — 권한 미발급 시 동작:
      현재 발급 키가 개인회원 키이면 외부 API 가 사용 거부 응답을 반환한다.
      이 경우 도구는 예외를 던지지 않고 정상 4필드 응답을 반환하되,
      structured.permission_denied=True / metadata.api_status="permission_denied" /
      text 안내 메시지를 포함해 호출자가 분기할 수 있게 한다.

    Raises:
        JobsConfigError: KEIS_WORKNET_JOB_API_KEY 미설정.
        JobsNormalizationError: 권한 거부 외의 응답 형식 오류 (XML 파싱 등).
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.keis_worknet_job_api_key:
        raise JobsConfigError(
            "KEIS_WORKNET_JOB_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await _resolve_source_id(_TOOL_WORKNET_JOB)
    params: dict[str, Any] = {
        "authKey": settings.keis_worknet_job_api_key,
        "callTp": "L",
        "returnType": "XML",
        "startPage": input.start_page,
        "display": input.display,
    }
    if input.keyword:
        params["keyword"] = input.keyword

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    source_url = _build_source_url(raw, params, secret_keys=("authKey",))
    fetched_at_iso = _fetched_at(raw)

    try:
        records: list[WorknetJobPosting] = normalize_worknet_job(raw.content)
    except WorknetPermissionDeniedError as exc:
        return _build_permission_denied_response(
            message=str(exc),
            keyword=input.keyword,
            params=params,
            source_url=source_url,
            fetched_at_iso=fetched_at_iso,
            source_id=source_id,
        )

    summary = _summarize_worknet(records)

    sorted_records = sorted(
        records,
        key=lambda r: r.reg_date or date.min,
        reverse=True,
    )
    truncated = len(sorted_records) > _MAX_RESULTS
    returned = sorted_records[:_MAX_RESULTS]

    return {
        "text": _worknet_text(input.keyword, summary),
        "structured": {
            "summary": summary,
            "postings": [r.model_dump(mode="json") for r in returned],
            "postings_truncated": truncated,
            "permission_denied": False,
            "query": {
                "keyword": input.keyword,
                "page_no": input.start_page,
                "display": input.display,
            },
        },
        "source_url": source_url,
        "metadata": {
            "fetched_at": fetched_at_iso,
            "raw_count": len(records),
            "returned_count": len(returned),
            "tool_name": _TOOL_WORKNET_JOB,
            "source_id": source_id,
            "api_status": "ok",
        },
    }


__all__ = [
    "SearchPublicJobInput",
    "SearchWorknetJobInput",
    "search_public_job",
    "search_worknet_job",
]
