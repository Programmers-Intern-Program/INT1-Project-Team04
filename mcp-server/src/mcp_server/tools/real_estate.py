"""부동산 도메인 MCP 도구.

현재 등록 도구:
- search_house_price: 국토교통부 아파트 매매 실거래가 조회.

원칙:
- serviceKey 같은 비밀값은 도구 함수 인자로 받지 않는다 (Langfuse @traced 가 입력을
  자동 캡처하므로 평문 누출 위험). 도구 내부에서 settings 로 직접 합성한다.
- 외부 호출은 sources.api_source_service.fetch() 만 경유한다.
- 결과 trades 는 토큰 폭증을 막기 위해 상위 _MAX_TRADES_RETURNED 건으로 절단한다.
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
    AptTrade,
    normalize_apt_trade,
)
from mcp_server.domains.real_estate.region import resolve_lawd_cd
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service

_TOOL_NAME = "search_house_price"
_DEFAULT_NUM_OF_ROWS = 100
_MAX_TRADES_RETURNED = 20

# 첫 호출 시 lookup 후 보존. seed 가 멱등이라 id 는 변하지 않는다.
# 테스트는 monkeypatch 로 None 으로 되돌린다.
_cached_source_id: int | None = None


class SearchHousePriceInput(BaseModel):
    """search_house_price 입력.

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


async def _resolve_source_id() -> int:
    global _cached_source_id
    if _cached_source_id is None:
        _cached_source_id = await api_source_service.resolve_source_id_by_tool_name(_TOOL_NAME)
    return _cached_source_id


def _mask_query_string(params: dict[str, Any]) -> str:
    """source_url 노출용 query string. serviceKey 는 마스킹.

    safe="*" 로 마스킹 문자가 percent-encode 되지 않도록 한다 (가독성).
    """
    masked = {k: ("***" if k == "serviceKey" else v) for k, v in params.items()}
    return urlencode(masked, safe="*")


def _summarize(trades: list[AptTrade]) -> dict[str, Any]:
    """간단한 통계 요약 (만원 단위 정수)."""
    if not trades:
        return {"count": 0, "avg_deal_amount": None, "min_deal_amount": None, "max_deal_amount": None}
    amounts = [t.deal_amount for t in trades]
    return {
        "count": len(trades),
        "avg_deal_amount": round(sum(amounts) / len(amounts)),
        "min_deal_amount": min(amounts),
        "max_deal_amount": max(amounts),
    }


def _to_eok(amount_manwon: int | None) -> str:
    """만원 → '12.5억' 표기. None 이면 '-'."""
    if amount_manwon is None:
        return "-"
    return f"{amount_manwon / 10000:.1f}억"


def _build_text(region_label: str, deal_ymd: str, summary: dict[str, Any]) -> str:
    if summary["count"] == 0:
        return f"{region_label} {deal_ymd} 아파트 매매 실거래 0건."
    return (
        f"{region_label} {deal_ymd} 아파트 매매 실거래 {summary['count']}건. "
        f"평균 {_to_eok(summary['avg_deal_amount'])}, "
        f"최저 {_to_eok(summary['min_deal_amount'])}, "
        f"최고 {_to_eok(summary['max_deal_amount'])} (단위: 만원)."
    )


@mcp.tool()
@traced(_TOOL_NAME)
async def search_house_price(input: SearchHousePriceInput) -> dict[str, Any]:
    """국토교통부 아파트 매매 실거래가 조회.

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
        RealEstateConfigError: MOLIT_TRADE_API_KEY 미설정.
        RealEstateRegionNotFoundError: region 매칭 실패 또는 동음이의(자동 선택 거부).
        RealEstateNormalizationError: API 응답이 비정상(코드 != "000") 또는 XML 파싱 실패.
        SourceNotFoundError: api_source 시드 미실행.
        SourceFetchError: API 호출 실패 (네트워크/4xx/5xx).
    """
    settings = get_settings()
    if not settings.molit_trade_api_key:
        raise RealEstateConfigError(
            "MOLIT_TRADE_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    lawd_cd = resolve_lawd_cd(input.region)
    source_id = await _resolve_source_id()

    params: dict[str, Any] = {
        "serviceKey": settings.molit_trade_api_key,
        "LAWD_CD": lawd_cd,
        "DEAL_YMD": input.deal_ymd,
        "numOfRows": _DEFAULT_NUM_OF_ROWS,
    }

    raw = await api_source_service.fetch(source_id=source_id, params=params)
    trades = normalize_apt_trade(raw.content, lawd_cd=lawd_cd)

    sorted_trades = sorted(trades, key=lambda t: t.deal_amount, reverse=True)
    truncated = len(sorted_trades) > _MAX_TRADES_RETURNED
    returned = sorted_trades[:_MAX_TRADES_RETURNED]
    summary = _summarize(trades)

    region_label = f"LAWD_CD {lawd_cd}"
    url_template = raw.raw_metadata.get("url_template", "")
    source_url = f"{url_template}?{_mask_query_string(params)}" if url_template else None

    return {
        "text": _build_text(region_label, input.deal_ymd, summary),
        "structured": {
            "summary": summary,
            "trades": [t.model_dump(mode="json") for t in returned],
            "trades_truncated": truncated,
            "query": {
                "region": input.region,
                "lawd_cd": lawd_cd,
                "deal_ymd": input.deal_ymd,
            },
        },
        "source_url": source_url,
        "metadata": {
            "fetched_at": raw.fetched_at.isoformat() if isinstance(raw.fetched_at, datetime) else str(raw.fetched_at),
            "raw_count": len(trades),
            "returned_count": len(returned),
            "tool_name": _TOOL_NAME,
            "source_id": source_id,
        },
    }


__all__ = ["SearchHousePriceInput", "search_house_price"]
