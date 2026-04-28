"""부동산 도메인 MCP 도구.

등록 도구 (6종):
- search_house_price (= 아파트 매매)
- search_apt_rent          (아파트 전월세)
- search_offi_trade        (오피스텔 매매)
- search_offi_rent         (오피스텔 전월세)
- search_rh_rent           (연립다세대 전월세)
- search_rh_trade          (연립다세대 매매)

원칙:
- serviceKey 같은 비밀값은 도구 함수 인자로 받지 않는다 (Langfuse @traced 가 입력을
  자동 캡처하므로 평문 누출 위험). 도구 내부에서 settings 로 직접 합성한다.
- 외부 호출은 sources.api_source_service.fetch() 만 경유한다.
- 결과 trades 는 토큰 폭증을 막기 위해 상위 _MAX_TRADES_RETURNED 건으로 절단한다.
- 도구 이름·docstring 은 LLM 의 도구 선택 근거. 자료유형(매매/전월세, 아파트/오피스텔
  /연립다세대)을 명확히 구분되게 작성. (CLAUDE.md "Tool 이름과 Description이 AI의
  판단 근거" 원칙)
"""

from datetime import datetime
from typing import Any
from urllib.parse import urlencode

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.real_estate.errors import (
    RealEstateConfigError,
)
from mcp_server.domains.real_estate.normalizer import (
    AptRent,
    AptTrade,
    OffiRent,
    OffiTrade,
    RHRent,
    RHTrade,
    normalize_apt_rent,
    normalize_apt_trade,
    normalize_offi_rent,
    normalize_offi_trade,
    normalize_rh_rent,
    normalize_rh_trade,
)
from mcp_server.domains.real_estate.region import resolve_lawd_cd
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service

_DEFAULT_NUM_OF_ROWS = 100
_MAX_TRADES_RETURNED = 20

# tool_name → api_source.id 캐시. seed 가 멱등이라 id 는 변하지 않는다.
# 테스트는 monkeypatch 로 dict 자체를 비운다.
_cached_source_ids: dict[str, int] = {}


# ─────────────────────────────────────────────
# 공통 입력 모델 — 5종 모두 region + deal_ymd 시그니처 동일.
# ─────────────────────────────────────────────

class MolitRealEstateInput(BaseModel):
    """MOLIT 부동산 실거래가 조회 공통 입력.

    region 은 자연어 시군구명("강남구", "서울 중구", "성남시 분당구") 또는 5자리
    법정동코드("11680") 모두 허용. 동·읍·면 단위는 지원하지 않는다.
    """

    region: str = Field(
        description=(
            "시군구명 또는 법정동코드 5자리. 예: '강남구', '서울 중구', "
            "'성남시 분당구', '11680'. 동음이의('중구', '동구' 등)는 시도와 함께 "
            "입력하세요(예: '서울 중구')."
        ),
    )
    deal_ymd: str = Field(
        pattern=r"^[0-9]{6}$",
        description="거래 연월 YYYYMM. 예: '202403'.",
        examples=["202403"],
    )


# 기존 호출자 호환을 위한 alias.
SearchHousePriceInput = MolitRealEstateInput


# ─────────────────────────────────────────────
# 공통 헬퍼
# ─────────────────────────────────────────────

async def _resolve_source_id(tool_name: str) -> int:
    """tool_name → api_source.id. 첫 호출 시 lookup 후 dict 캐시."""
    if tool_name not in _cached_source_ids:
        _cached_source_ids[tool_name] = (
            await api_source_service.resolve_source_id_by_tool_name(tool_name)
        )
    return _cached_source_ids[tool_name]


def _mask_query_string(params: dict[str, Any]) -> str:
    """source_url 노출용 query string. serviceKey 는 마스킹.

    safe="*" 로 마스킹 문자가 percent-encode 되지 않도록 한다 (가독성).
    """
    masked = {k: ("***" if k == "serviceKey" else v) for k, v in params.items()}
    return urlencode(masked, safe="*")


def _to_eok(amount_manwon: int | None) -> str:
    """만원 → '12.5억' 표기. None 이면 '-'."""
    if amount_manwon is None:
        return "-"
    return f"{amount_manwon / 10000:.1f}억"


def _build_params(api_key: str, lawd_cd: str, deal_ymd: str) -> dict[str, Any]:
    return {
        "serviceKey": api_key,
        "LAWD_CD": lawd_cd,
        "DEAL_YMD": deal_ymd,
        "numOfRows": _DEFAULT_NUM_OF_ROWS,
    }


# ─────────────────────────────────────────────
# 매매 (Trade) 공통: dealAmount 기준 통계
# ─────────────────────────────────────────────

def _summarize_trade(records: list) -> dict[str, Any]:
    if not records:
        return {
            "count": 0,
            "avg_deal_amount": None,
            "min_deal_amount": None,
            "max_deal_amount": None,
        }
    amounts = [r.deal_amount for r in records]
    return {
        "count": len(records),
        "avg_deal_amount": round(sum(amounts) / len(amounts)),
        "min_deal_amount": min(amounts),
        "max_deal_amount": max(amounts),
    }


def _trade_text(region_label: str, deal_ymd: str, kind: str, summary: dict[str, Any]) -> str:
    """매매 자료유형 통계 한 단락. kind 예: '아파트 매매', '오피스텔 매매'."""
    if summary["count"] == 0:
        return f"{region_label} {deal_ymd} {kind} 실거래 0건."
    return (
        f"{region_label} {deal_ymd} {kind} 실거래 {summary['count']}건. "
        f"평균 {_to_eok(summary['avg_deal_amount'])}, "
        f"최저 {_to_eok(summary['min_deal_amount'])}, "
        f"최고 {_to_eok(summary['max_deal_amount'])} (단위: 만원)."
    )


# ─────────────────────────────────────────────
# 전월세 (Rent) 공통: deposit 기준 통계 + 평균 월세
# ─────────────────────────────────────────────

def _summarize_rent(records: list) -> dict[str, Any]:
    if not records:
        return {
            "count": 0,
            "avg_deposit": None,
            "min_deposit": None,
            "max_deposit": None,
            "avg_monthly_rent": None,
        }
    deposits = [r.deposit for r in records]
    monthly = [r.monthly_rent for r in records]
    return {
        "count": len(records),
        "avg_deposit": round(sum(deposits) / len(deposits)),
        "min_deposit": min(deposits),
        "max_deposit": max(deposits),
        "avg_monthly_rent": round(sum(monthly) / len(monthly)),
    }


def _rent_text(region_label: str, deal_ymd: str, kind: str, summary: dict[str, Any]) -> str:
    """전월세 통계 한 단락. monthly 0 이면 전세, 0 초과면 월세 포함."""
    if summary["count"] == 0:
        return f"{region_label} {deal_ymd} {kind} 실거래 0건."
    return (
        f"{region_label} {deal_ymd} {kind} 실거래 {summary['count']}건. "
        f"평균 보증금 {_to_eok(summary['avg_deposit'])}, "
        f"최저 보증금 {_to_eok(summary['min_deposit'])}, "
        f"최고 보증금 {_to_eok(summary['max_deposit'])}, "
        f"평균 월세 {summary['avg_monthly_rent']}만원."
    )


# ─────────────────────────────────────────────
# 응답 dict 빌더 — fetch + normalize 후 정렬·절단·요약·메타
# ─────────────────────────────────────────────

def _build_response(
    *,
    raw,
    records: list,
    sort_key,
    summary: dict[str, Any],
    text: str,
    region: str,
    lawd_cd: str,
    deal_ymd: str,
    params: dict[str, Any],
    tool_name: str,
    source_id: int,
) -> dict[str, Any]:
    sorted_records = sorted(records, key=sort_key, reverse=True)
    truncated = len(sorted_records) > _MAX_TRADES_RETURNED
    returned = sorted_records[:_MAX_TRADES_RETURNED]

    url_template = raw.raw_metadata.get("url_template", "")
    source_url = f"{url_template}?{_mask_query_string(params)}" if url_template else None

    return {
        "text": text,
        "structured": {
            "summary": summary,
            "trades": [r.model_dump(mode="json") for r in returned],
            "trades_truncated": truncated,
            "query": {
                "region": region,
                "lawd_cd": lawd_cd,
                "deal_ymd": deal_ymd,
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
            "tool_name": tool_name,
            "source_id": source_id,
        },
    }


# ─────────────────────────────────────────────
# 도구 1: 아파트 매매 (= search_house_price, 기존 호환 유지)
# ─────────────────────────────────────────────

_TOOL_APT_TRADE = "search_house_price"


@mcp.tool()
@traced(_TOOL_APT_TRADE)
async def search_house_price(input: MolitRealEstateInput) -> dict[str, Any]:
    """국토교통부 **아파트 매매** 실거래가 조회.

    지정한 시군구(LAWD_CD 5자리) + 거래연월(YYYYMM) 의 아파트 매매 실거래 목록을
    국토교통부 공식 API 에서 조회한다.

    반환 dict:
      - text: 통계 요약 한 단락 (사람 읽기, 억 단위 환산 표기).
      - structured.summary: {count, avg_deal_amount, min_deal_amount, max_deal_amount}
        (단위: 만원, 정수). 거래 0건이면 통계 필드는 None.
      - structured.trades: AptTrade dict 의 상위 20건 (가격 내림차순).
      - structured.trades_truncated: 원본이 20건 초과로 절단됐으면 True.
      - structured.query: 호출 인자 echo (region, lawd_cd, deal_ymd).
      - source_url: 호출한 API URL + query (serviceKey 는 '***' 로 마스킹).
      - metadata: {fetched_at, raw_count, returned_count, tool_name, source_id}.

    빈 응답(거래 0건) 은 정상 동작 — text 가 "0건" 으로 명시된다. 예외 아님.

    Raises:
        RealEstateConfigError: MOLIT_APT_TRADE_API_KEY 미설정.
        RealEstateRegionNotFoundError: region 매칭 실패 또는 동음이의(자동 선택 거부).
        RealEstateNormalizationError: API 응답이 비정상(코드 != "000") 또는 XML 파싱 실패.
        SourceNotFoundError: api_source 시드 미실행.
        SourceFetchError: API 호출 실패 (네트워크/4xx/5xx).
    """
    settings = get_settings()
    if not settings.molit_apt_trade_api_key:
        raise RealEstateConfigError(
            "MOLIT_APT_TRADE_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id(_TOOL_APT_TRADE)
    params = _build_params(settings.molit_apt_trade_api_key, lawd_cd, input.deal_ymd)

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[AptTrade] = normalize_apt_trade(raw.content, lawd_cd=lawd_cd)
    summary = _summarize_trade(records)

    return _build_response(
        raw=raw,
        records=records,
        sort_key=lambda r: r.deal_amount,
        summary=summary,
        text=_trade_text(f"LAWD_CD {lawd_cd}", input.deal_ymd, "아파트 매매", summary),
        region=input.region,
        lawd_cd=lawd_cd,
        deal_ymd=input.deal_ymd,
        params=params,
        tool_name=_TOOL_APT_TRADE,
        source_id=source_id,
    )


# ─────────────────────────────────────────────
# 도구 2: 아파트 전월세
# ─────────────────────────────────────────────

_TOOL_APT_RENT = "search_apt_rent"


@mcp.tool()
@traced(_TOOL_APT_RENT)
async def search_apt_rent(input: MolitRealEstateInput) -> dict[str, Any]:
    """국토교통부 **아파트 전월세** 실거래가 조회 (전세·월세 모두 포함).

    지정한 시군구(LAWD_CD 5자리) + 거래연월(YYYYMM) 의 아파트 전월세 실거래 목록을
    국토교통부 공식 API 에서 조회한다. 전세는 monthly_rent=0 으로 표현된다.

    반환 dict:
      - text: 통계 요약 (보증금 평균/최저/최고 + 평균 월세).
      - structured.summary: {count, avg_deposit, min_deposit, max_deposit, avg_monthly_rent}
        (단위: 만원). 거래 0건이면 통계 필드는 None.
      - structured.trades: AptRent dict 의 상위 20건 (보증금 내림차순).
      - structured.trades_truncated / query / source_url / metadata: 매매 도구와 동일.

    Raises:
        RealEstateConfigError: MOLIT_APT_RENT_API_KEY 미설정.
        (그 외 매매 도구와 동일.)
    """
    settings = get_settings()
    if not settings.molit_apt_rent_api_key:
        raise RealEstateConfigError(
            "MOLIT_APT_RENT_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id(_TOOL_APT_RENT)
    params = _build_params(settings.molit_apt_rent_api_key, lawd_cd, input.deal_ymd)

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[AptRent] = normalize_apt_rent(raw.content, lawd_cd=lawd_cd)
    summary = _summarize_rent(records)

    return _build_response(
        raw=raw,
        records=records,
        sort_key=lambda r: r.deposit,
        summary=summary,
        text=_rent_text(f"LAWD_CD {lawd_cd}", input.deal_ymd, "아파트 전월세", summary),
        region=input.region,
        lawd_cd=lawd_cd,
        deal_ymd=input.deal_ymd,
        params=params,
        tool_name=_TOOL_APT_RENT,
        source_id=source_id,
    )


# ─────────────────────────────────────────────
# 도구 3: 오피스텔 매매
# ─────────────────────────────────────────────

_TOOL_OFFI_TRADE = "search_offi_trade"


@mcp.tool()
@traced(_TOOL_OFFI_TRADE)
async def search_offi_trade(input: MolitRealEstateInput) -> dict[str, Any]:
    """국토교통부 **오피스텔 매매** 실거래가 조회.

    지정한 시군구(LAWD_CD 5자리) + 거래연월(YYYYMM) 의 오피스텔 매매 실거래 목록을
    국토교통부 공식 API 에서 조회한다. 아파트가 아닌 오피스텔(officetel) 전용.

    반환 dict 구조는 아파트 매매(search_house_price) 와 동일하나, 단지명 필드는
    offi_name 으로 노출된다.

    Raises:
        RealEstateConfigError: MOLIT_OFFI_TRADE_API_KEY 미설정.
        (그 외 매매 도구와 동일.)
    """
    settings = get_settings()
    if not settings.molit_offi_trade_api_key:
        raise RealEstateConfigError(
            "MOLIT_OFFI_TRADE_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id(_TOOL_OFFI_TRADE)
    params = _build_params(settings.molit_offi_trade_api_key, lawd_cd, input.deal_ymd)

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[OffiTrade] = normalize_offi_trade(raw.content, lawd_cd=lawd_cd)
    summary = _summarize_trade(records)

    return _build_response(
        raw=raw,
        records=records,
        sort_key=lambda r: r.deal_amount,
        summary=summary,
        text=_trade_text(f"LAWD_CD {lawd_cd}", input.deal_ymd, "오피스텔 매매", summary),
        region=input.region,
        lawd_cd=lawd_cd,
        deal_ymd=input.deal_ymd,
        params=params,
        tool_name=_TOOL_OFFI_TRADE,
        source_id=source_id,
    )


# ─────────────────────────────────────────────
# 도구 4: 오피스텔 전월세
# ─────────────────────────────────────────────

_TOOL_OFFI_RENT = "search_offi_rent"


@mcp.tool()
@traced(_TOOL_OFFI_RENT)
async def search_offi_rent(input: MolitRealEstateInput) -> dict[str, Any]:
    """국토교통부 **오피스텔 전월세** 실거래가 조회 (전세·월세 모두 포함).

    지정한 시군구(LAWD_CD 5자리) + 거래연월(YYYYMM) 의 오피스텔 전월세 실거래 목록을
    국토교통부 공식 API 에서 조회한다. 아파트가 아닌 오피스텔(officetel) 전용.
    전세는 monthly_rent=0 으로 표현된다.

    반환 dict 구조는 아파트 전월세(search_apt_rent) 와 동일하나, 단지명 필드는
    offi_name 으로 노출된다.

    Raises:
        RealEstateConfigError: MOLIT_OFFI_RENT_API_KEY 미설정.
        (그 외 동일.)
    """
    settings = get_settings()
    if not settings.molit_offi_rent_api_key:
        raise RealEstateConfigError(
            "MOLIT_OFFI_RENT_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id(_TOOL_OFFI_RENT)
    params = _build_params(settings.molit_offi_rent_api_key, lawd_cd, input.deal_ymd)

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[OffiRent] = normalize_offi_rent(raw.content, lawd_cd=lawd_cd)
    summary = _summarize_rent(records)

    return _build_response(
        raw=raw,
        records=records,
        sort_key=lambda r: r.deposit,
        summary=summary,
        text=_rent_text(f"LAWD_CD {lawd_cd}", input.deal_ymd, "오피스텔 전월세", summary),
        region=input.region,
        lawd_cd=lawd_cd,
        deal_ymd=input.deal_ymd,
        params=params,
        tool_name=_TOOL_OFFI_RENT,
        source_id=source_id,
    )


# ─────────────────────────────────────────────
# 도구 5: 연립다세대 전월세
# ─────────────────────────────────────────────

_TOOL_RH_RENT = "search_rh_rent"


@mcp.tool()
@traced(_TOOL_RH_RENT)
async def search_rh_rent(input: MolitRealEstateInput) -> dict[str, Any]:
    """국토교통부 **연립다세대(빌라·연립·다세대주택) 전월세** 실거래가 조회.

    지정한 시군구(LAWD_CD 5자리) + 거래연월(YYYYMM) 의 연립·다세대주택 전월세
    실거래 목록을 국토교통부 공식 API 에서 조회한다. 아파트나 오피스텔이 아닌
    빌라/연립/다세대주택이 대상. 전세는 monthly_rent=0 으로 표현된다.

    반환 dict 의 trades 항목엔 단지명(mhouse_name) 외에 house_type(예: '연립',
    '다세대') 필드가 포함된다.

    Raises:
        RealEstateConfigError: MOLIT_RH_RENT_API_KEY 미설정.
        (그 외 동일.)
    """
    settings = get_settings()
    if not settings.molit_rh_rent_api_key:
        raise RealEstateConfigError(
            "MOLIT_RH_RENT_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id(_TOOL_RH_RENT)
    params = _build_params(settings.molit_rh_rent_api_key, lawd_cd, input.deal_ymd)

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[RHRent] = normalize_rh_rent(raw.content, lawd_cd=lawd_cd)
    summary = _summarize_rent(records)

    return _build_response(
        raw=raw,
        records=records,
        sort_key=lambda r: r.deposit,
        summary=summary,
        text=_rent_text(f"LAWD_CD {lawd_cd}", input.deal_ymd, "연립다세대 전월세", summary),
        region=input.region,
        lawd_cd=lawd_cd,
        deal_ymd=input.deal_ymd,
        params=params,
        tool_name=_TOOL_RH_RENT,
        source_id=source_id,
    )


# ─────────────────────────────────────────────
# 도구 6: 연립다세대 매매
# ─────────────────────────────────────────────

_TOOL_RH_TRADE = "search_rh_trade"


@mcp.tool()
@traced(_TOOL_RH_TRADE)
async def search_rh_trade(input: MolitRealEstateInput) -> dict[str, Any]:
    """국토교통부 **연립다세대(빌라·연립·다세대주택) 매매** 실거래가 조회.

    지정한 시군구(LAWD_CD 5자리) + 거래연월(YYYYMM) 의 연립·다세대주택 매매
    실거래 목록을 국토교통부 공식 API 에서 조회한다. 아파트나 오피스텔이 아닌
    빌라/연립/다세대주택이 대상.

    반환 dict 의 trades 항목엔 단지명(mhouse_name) 외에 house_type(예: '연립',
    '다세대') 필드가 포함된다. 정렬은 거래금액 내림차순.

    Raises:
        RealEstateConfigError: MOLIT_RH_TRADE_API_KEY 미설정.
        (그 외 매매 도구와 동일.)
    """
    settings = get_settings()
    if not settings.molit_rh_trade_api_key:
        raise RealEstateConfigError(
            "MOLIT_RH_TRADE_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id(_TOOL_RH_TRADE)
    params = _build_params(settings.molit_rh_trade_api_key, lawd_cd, input.deal_ymd)

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    records: list[RHTrade] = normalize_rh_trade(raw.content, lawd_cd=lawd_cd)
    summary = _summarize_trade(records)

    return _build_response(
        raw=raw,
        records=records,
        sort_key=lambda r: r.deal_amount,
        summary=summary,
        text=_trade_text(f"LAWD_CD {lawd_cd}", input.deal_ymd, "연립다세대 매매", summary),
        region=input.region,
        lawd_cd=lawd_cd,
        deal_ymd=input.deal_ymd,
        params=params,
        tool_name=_TOOL_RH_TRADE,
        source_id=source_id,
    )


__all__ = [
    "MolitRealEstateInput",
    "SearchHousePriceInput",  # 호환 alias
    "search_apt_rent",
    "search_house_price",
    "search_offi_rent",
    "search_offi_trade",
    "search_rh_rent",
    "search_rh_trade",
]
