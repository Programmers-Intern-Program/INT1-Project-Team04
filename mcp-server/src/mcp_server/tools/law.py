"""법률 도메인 MCP 도구 (임시 minimal passthrough).

등록 도구 (2종):
- search_law_info  — 법제처 국가법령정보 공유서비스 (data.go.kr/data/15000115)
- search_bill_info — 국회사무처 의안정보 통합 API (data.go.kr/data/15126134)

원칙:
- serviceKey 같은 비밀값은 도구 함수 인자로 받지 않는다 (Langfuse @traced 입력 캡처).
- 외부 호출은 sources.api_source_service.fetch() 만 경유.
- 정규화/통계는 본작업에서 추가. 현재는 raw passthrough.
"""

from typing import Any

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.law.errors import LawConfigError
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service
from mcp_server.tools._passthrough import (
    build_passthrough_response,
    resolve_source_id,
)

_DEFAULT_NUM_OF_ROWS = 20

# ─────────────────────────────────────────────
# 도구 1: 법제처 국가법령정보
# ─────────────────────────────────────────────

_TOOL_LAW_INFO = "search_law_info"


class SearchLawInfoInput(BaseModel):
    """현행법령 목록조회 입력 (법제처 명세 기준).

    응답은 XML 고정 — 명세 상 JSON 옵션 없음. 정규화/필드 추출은 추가 예정.
    """

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


@mcp.tool()
@traced(_TOOL_LAW_INFO)
async def search_law_info(input: SearchLawInfoInput) -> dict[str, Any]:
    """법제처 **국가법령정보 공유서비스 — 현행법령 목록조회**.

    검색어로 현행 법령 목록(target=law)을 조회한다. 응답은 XML 이며 외부 API 가
    돌려준 원본을 그대로 {text, structured.raw, source_url, metadata} 공통 스키마로
    노출한다 (정규화는 보강 예정).

    Raises:
        LawConfigError: MOLEG_LAW_INFO_API_KEY 미설정.
        SourceNotFoundError: api_source 시드 미실행.
        SourceFetchError: 외부 API 호출 실패 (네트워크/4xx/5xx).
    """
    settings = get_settings()
    if not settings.moleg_law_info_api_key:
        raise LawConfigError(
            "MOLEG_LAW_INFO_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await resolve_source_id(_TOOL_LAW_INFO)
    # 명세 기준 5개 필수 파라미터 (인증키/서비스대상/검색어/페이지수/페이지번호).
    params: dict[str, Any] = {
        "serviceKey": settings.moleg_law_info_api_key,
        "target": "law",
        "query": input.query,
        "numOfRows": input.num_of_rows,
        "pageNo": input.page_no,
    }
    raw = await api_source_service.fetch(source_id=source_id, params=params)

    return build_passthrough_response(
        raw=raw,
        tool_name=_TOOL_LAW_INFO,
        source_id=source_id,
        params=params,
        secret_keys=("serviceKey",),
        summary_text=(
            f"현행법령 목록조회 '{input.query}' (page={input.page_no}, "
            f"rows={input.num_of_rows}) 응답 {len(raw.content or '')}자 수신."
        ),
    )


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


@mcp.tool()
@traced(_TOOL_BILL_INFO)
async def search_bill_info(input: SearchBillInfoInput) -> dict[str, Any]:
    """국회사무처 **의안정보** 조회 (열린국회정보 기반).

    지정 대수의 의안 목록을 조회한다. 응답은 외부 API 가 돌려준 원본을 그대로
    {text, structured.raw, source_url, metadata} 공통 스키마로 노출한다.

    Raises:
        LawConfigError: NA_BILL_INFO_API_KEY 미설정.
        SourceNotFoundError / SourceFetchError: 위와 동일.
    """
    settings = get_settings()
    if not settings.na_bill_info_api_key:
        raise LawConfigError(
            "NA_BILL_INFO_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await resolve_source_id(_TOOL_BILL_INFO)
    # 열린국회정보(open.assembly.go.kr) 파라미터 규약:
    #   KEY    — 인증키
    #   Type   — 응답 형식 (xml/json)
    #   pIndex — 페이지 번호 (1부터)
    #   pSize  — 페이지당 결과 수
    params: dict[str, Any] = {
        "KEY": settings.na_bill_info_api_key,
        "Type": "xml",
        "pIndex": input.page_no,
        "pSize": input.num_of_rows,
        "AGE": input.age,
    }
    raw = await api_source_service.fetch(source_id=source_id, params=params)

    return build_passthrough_response(
        raw=raw,
        tool_name=_TOOL_BILL_INFO,
        source_id=source_id,
        params=params,
        secret_keys=("KEY",),
        summary_text=(
            f"의안 검색 (제{input.age}대, page={input.page_no}, "
            f"rows={input.num_of_rows}) 응답 {len(raw.content or '')}자 수신."
        ),
    )


__all__ = [
    "SearchBillInfoInput",
    "SearchLawInfoInput",
    "search_bill_info",
    "search_law_info",
]
