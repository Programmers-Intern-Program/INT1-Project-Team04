"""경매 도메인 MCP 도구.

등록 도구 (1종):
- search_g2b_bid — 조달청 나라장터 입찰공고정보서비스 (data.go.kr/data/15129394)

원칙:
- serviceKey 는 도구 인자로 받지 않는다 (Langfuse @traced 가 입력 자동 캡처).
- 외부 호출은 sources.api_source_service.fetch() 만 경유.
- 토큰 절약을 위해 상위 _MAX_RESULTS 건만 반환 (공고일시 내림차순).

조달청 입찰공고는 본질적으로 경매(낙찰)와 다른 발주 행위지만, 본 프로젝트의 4개
도메인 분류상 "경매" 카테고리에 속한다.
"""

from datetime import datetime, timedelta
from typing import Any
from urllib.parse import urlencode

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.auction.errors import AuctionConfigError
from mcp_server.domains.auction.normalizer import BidNotice, normalize_g2b_bid
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service

_DEFAULT_NUM_OF_ROWS = 20
_DEFAULT_LOOKBACK_DAYS = 7
_MAX_RESULTS = 20

# tool_name → api_source.id 캐시 (real_estate.py 와 동일 패턴)
_cached_source_ids: dict[str, int] = {}

_TOOL_G2B_BID = "search_g2b_bid"


def _yyyymmddhhmm(dt: datetime) -> str:
    """나라장터 API 의 일자 파라미터 포맷 (YYYYMMDDHHMM)."""
    return dt.strftime("%Y%m%d%H%M")


class SearchG2bBidInput(BaseModel):
    """나라장터 입찰공고 검색 입력."""

    inqry_bgn_dt: str | None = Field(
        default=None,
        pattern=r"^[0-9]{12}$",
        description=(
            "조회 시작 일시 YYYYMMDDHHMM. 미지정 시 현재 기준 7일 전 동시각. "
            "예: '202604010000'."
        ),
    )
    inqry_end_dt: str | None = Field(
        default=None,
        pattern=r"^[0-9]{12}$",
        description="조회 종료 일시 YYYYMMDDHHMM. 미지정 시 현재 시각.",
    )
    page_no: int = Field(default=1, ge=1, description="페이지 번호 (1부터).")
    num_of_rows: int = Field(
        default=_DEFAULT_NUM_OF_ROWS,
        ge=1,
        le=100,
        description="페이지당 결과 수 (1~100).",
    )


async def _resolve_source_id(tool_name: str) -> int:
    if tool_name not in _cached_source_ids:
        _cached_source_ids[tool_name] = (
            await api_source_service.resolve_source_id_by_tool_name(tool_name)
        )
    return _cached_source_ids[tool_name]


def _mask_query_string(params: dict[str, Any]) -> str:
    """source_url 노출용 query string. serviceKey 는 마스킹."""
    masked = {k: ("***" if k == "serviceKey" else v) for k, v in params.items()}
    return urlencode(masked, safe="*")


def _to_eok(amount_won: int | None) -> str:
    """원 → '12.5억' / '5,000만' / '-'. 사람 읽기용 표기."""
    if amount_won is None:
        return "-"
    if amount_won >= 100_000_000:
        return f"{amount_won / 100_000_000:.1f}억"
    if amount_won >= 10_000:
        return f"{amount_won / 10_000:,.0f}만"
    return f"{amount_won:,}원"


def _summarize(records: list[BidNotice]) -> dict[str, Any]:
    if not records:
        return {
            "count": 0,
            "avg_estimated_price": None,
            "min_estimated_price": None,
            "max_estimated_price": None,
            "total_assigned_budget": None,
        }
    estimated = [r.estimated_price for r in records if r.estimated_price]
    budgets = [r.assigned_budget for r in records if r.assigned_budget]
    return {
        "count": len(records),
        "avg_estimated_price": round(sum(estimated) / len(estimated)) if estimated else None,
        "min_estimated_price": min(estimated) if estimated else None,
        "max_estimated_price": max(estimated) if estimated else None,
        "total_assigned_budget": sum(budgets) if budgets else None,
    }


def _build_text(bgn_dt: str, end_dt: str, summary: dict[str, Any]) -> str:
    if summary["count"] == 0:
        return f"나라장터 입찰공고 ({bgn_dt} ~ {end_dt}) 0건."
    return (
        f"나라장터 입찰공고 ({bgn_dt} ~ {end_dt}) {summary['count']}건. "
        f"평균 추정가 {_to_eok(summary['avg_estimated_price'])}, "
        f"최고 추정가 {_to_eok(summary['max_estimated_price'])}."
    )


@mcp.tool()
@traced(_TOOL_G2B_BID)
async def search_g2b_bid(input: SearchG2bBidInput) -> dict[str, Any]:
    """조달청 **나라장터 입찰공고** 목록 조회.

    지정 기간(inqry_bgn_dt ~ inqry_end_dt) 의 입찰공고 목록을 조회하고
    {text, structured, source_url, metadata} 공통 스키마로 반환한다.
    기본값: 시작/종료 일시 미지정 시 최근 7일.

    반환 dict:
      - text: 통계 요약 한 단락 (건수 + 평균/최고 추정가, 억/만 단위 표기).
      - structured.summary: {count, avg/min/max_estimated_price, total_assigned_budget}
        (단위: 원, 정수). 0건이면 통계 필드는 None.
      - structured.notices: BidNotice dict 의 상위 20건 (공고일시 내림차순).
      - structured.notices_truncated: 원본이 20건 초과로 절단됐으면 True.
      - structured.query: 호출 인자 echo (inqry_bgn_dt/end_dt/page_no/num_of_rows).
      - source_url: 호출한 API URL + query (serviceKey 는 '***' 로 마스킹).
      - metadata: {fetched_at, raw_count, returned_count, tool_name, source_id}.

    빈 응답(0건) 은 정상 동작 — text 가 "0건" 으로 명시된다. 예외 아님.

    Raises:
        AuctionConfigError: PPS_G2B_BID_API_KEY 미설정.
        AuctionNormalizationError: API 응답이 비정상(코드 != "00") 또는 JSON 파싱 실패.
        SourceNotFoundError: api_source 시드 미실행.
        SourceFetchError: API 호출 실패 (네트워크/4xx/5xx).
    """
    settings = get_settings()
    if not settings.pps_g2b_bid_api_key:
        raise AuctionConfigError(
            "PPS_G2B_BID_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    now = datetime.now()
    end_dt = input.inqry_end_dt or _yyyymmddhhmm(now)
    bgn_dt = input.inqry_bgn_dt or _yyyymmddhhmm(now - timedelta(days=_DEFAULT_LOOKBACK_DAYS))

    source_id = await _resolve_source_id(_TOOL_G2B_BID)
    params: dict[str, Any] = {
        "serviceKey": settings.pps_g2b_bid_api_key,
        "pageNo": input.page_no,
        "numOfRows": input.num_of_rows,
        "type": "json",
        "inqryDiv": 1,  # 1=등록일시 기준
        "inqryBgnDt": bgn_dt,
        "inqryEndDt": end_dt,
    }
    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[BidNotice] = normalize_g2b_bid(raw.content)
    summary = _summarize(records)

    sorted_records = sorted(records, key=lambda r: r.notice_date, reverse=True)
    truncated = len(sorted_records) > _MAX_RESULTS
    returned = sorted_records[:_MAX_RESULTS]

    url_template = raw.raw_metadata.get("url_template", "")
    source_url = f"{url_template}?{_mask_query_string(params)}" if url_template else None

    return {
        "text": _build_text(bgn_dt, end_dt, summary),
        "structured": {
            "summary": summary,
            "notices": [r.model_dump(mode="json") for r in returned],
            "notices_truncated": truncated,
            "query": {
                "inqry_bgn_dt": bgn_dt,
                "inqry_end_dt": end_dt,
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
            "tool_name": _TOOL_G2B_BID,
            "source_id": source_id,
        },
    }


__all__ = ["SearchG2bBidInput", "search_g2b_bid"]
