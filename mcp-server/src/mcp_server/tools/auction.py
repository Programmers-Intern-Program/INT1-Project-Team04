"""경매 도메인 MCP 도구 (임시 minimal passthrough).

등록 도구 (1종):
- search_g2b_bid — 조달청 나라장터 입찰공고정보서비스 (data.go.kr/data/15129394)

원칙:
- serviceKey 는 도구 인자로 받지 않는다 (Langfuse 입력 캡처 보호).
- 외부 호출은 sources.api_source_service.fetch() 만 경유.
- 정규화/통계는 추후 추가. 현재는 raw passthrough.

조달청 입찰공고는 본질적으로 경매(낙찰)와 다른 발주 행위지만, 본 프로젝트의 4개
도메인 분류상 "경매" 카테고리에 속한다 
"""

from datetime import datetime, timedelta
from typing import Any

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.auction.errors import AuctionConfigError
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service
from mcp_server.tools._passthrough import (
    build_passthrough_response,
    resolve_source_id,
)

_DEFAULT_NUM_OF_ROWS = 20
_DEFAULT_LOOKBACK_DAYS = 7

# ─────────────────────────────────────────────
# 도구: 조달청 나라장터 입찰공고
# ─────────────────────────────────────────────

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
            "조회 시작 일시 YYYYMMDDHHMM. 미지정 시 현재 기준 7일 전 0시. "
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


@mcp.tool()
@traced(_TOOL_G2B_BID)
async def search_g2b_bid(input: SearchG2bBidInput) -> dict[str, Any]:
    """조달청 **나라장터 입찰공고** 목록 조회.

    지정 기간(inqry_bgn_dt ~ inqry_end_dt) 의 입찰공고 목록을 조회한다.
    응답은 외부 API 가 돌려준 원본을 그대로 {text, structured.raw, source_url,
    metadata} 공통 스키마로 노출한다.

    기본값: 시작/종료 일시 미지정 시 최근 7일.

    Raises:
        AuctionConfigError: PPS_G2B_BID_API_KEY 미설정.
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.pps_g2b_bid_api_key:
        raise AuctionConfigError(
            "PPS_G2B_BID_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    now = datetime.now()
    end_dt = input.inqry_end_dt or _yyyymmddhhmm(now)
    bgn_dt = input.inqry_bgn_dt or _yyyymmddhhmm(now - timedelta(days=_DEFAULT_LOOKBACK_DAYS))

    source_id = await resolve_source_id(_TOOL_G2B_BID)
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

    return build_passthrough_response(
        raw=raw,
        tool_name=_TOOL_G2B_BID,
        source_id=source_id,
        params=params,
        secret_keys=("serviceKey",),
        summary_text=(
            f"나라장터 입찰공고 검색 ({bgn_dt} ~ {end_dt}, "
            f"page={input.page_no}, rows={input.num_of_rows}) "
            f"응답 {len(raw.content or '')}자 수신."
        ),
    )


__all__ = ["SearchG2bBidInput", "search_g2b_bid"]
