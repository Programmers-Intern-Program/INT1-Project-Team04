"""채용 도메인 MCP 도구 (임시 minimal passthrough).

등록 도구 (2종):
- search_public_job  — 기획재정부 공공기관 채용정보 (data.go.kr/data/15125273)
- search_worknet_job — 한국고용정보원 워크넷 채용정보 (data.go.kr/data/3038225)

원칙:
- serviceKey 는 도구 인자로 받지 않는다 (Langfuse 입력 캡처 보호).
- 외부 호출은 sources.api_source_service.fetch() 만 경유.
- 정규화/통계는 PLAN Phase 5 본작업에서 추가. 현재는 raw passthrough.

KEIS_WORKNET_JOB 주의:
  현재 발급된 키가 사용 불가 응답을 반환할 수 있음. 도구 자체는 정상 등록되어
  Spring AI 가 도구 목록에서 인식하고 호출까지 도달함을 확인하는 데 의의가 있다.
  키 발급/권한이 정상화되면 코드 수정 없이 동작.
"""

from typing import Any

from pydantic import BaseModel, Field

from mcp_server.config import get_settings
from mcp_server.domains.jobs.errors import JobsConfigError
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service
from mcp_server.tools._passthrough import (
    build_passthrough_response,
    resolve_source_id,
)

_DEFAULT_NUM_OF_ROWS = 20

# ─────────────────────────────────────────────
# 도구 1: 기획재정부 공공기관 채용
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


@mcp.tool()
@traced(_TOOL_PUBLIC_JOB)
async def search_public_job(input: SearchPublicJobInput) -> dict[str, Any]:
    """기획재정부 **공공기관 채용공시 목록조회**.

    공공기관 경영정보 공개시스템(ALIO) 기반 채용공시 목록 조회. 응답은 외부 API 가
    돌려준 원본을 그대로 {text, structured.raw, source_url, metadata} 공통 스키마로
    노출한다.

    Raises:
        JobsConfigError: MOEF_PUBLIC_JOB_API_KEY 미설정.
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.moef_public_job_api_key:
        raise JobsConfigError(
            "MOEF_PUBLIC_JOB_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await resolve_source_id(_TOOL_PUBLIC_JOB)
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

    return build_passthrough_response(
        raw=raw,
        tool_name=_TOOL_PUBLIC_JOB,
        source_id=source_id,
        params=params,
        secret_keys=("serviceKey",),
        summary_text=(
            f"공공기관 채용공시 목록조회 (page={input.page_no}, "
            f"rows={input.num_of_rows}) 응답 {len(raw.content or '')}자 수신."
        ),
    )


# ─────────────────────────────────────────────
# 도구 2: 워크넷 채용정보 (KEIS)
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


@mcp.tool()
@traced(_TOOL_WORKNET_JOB)
async def search_worknet_job(input: SearchWorknetJobInput) -> dict[str, Any]:
    """**워크넷 채용정보** 조회 (한국고용정보원).

    키워드로 채용 공고 목록을 조회한다. 응답은 외부 API 가 돌려준 원본을 그대로
    {text, structured.raw, source_url, metadata} 공통 스키마로 노출한다.

    참고: 현재 발급 키가 사용 불가(권한/심사) 응답을 반환할 수 있다.
    이 경우에도 도구 호출 경로(MCP → API 도달) 자체는 정상 동작하며,
    structured.raw 에 사용 불가 메시지가 그대로 노출된다.

    Raises:
        JobsConfigError: KEIS_WORKNET_JOB_API_KEY 미설정.
        SourceNotFoundError / SourceFetchError: 외부 호출 관련 오류.
    """
    settings = get_settings()
    if not settings.keis_worknet_job_api_key:
        raise JobsConfigError(
            "KEIS_WORKNET_JOB_API_KEY 환경변수 미설정. .env 또는 배포 환경에 키를 설정하세요."
        )

    source_id = await resolve_source_id(_TOOL_WORKNET_JOB)
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

    return build_passthrough_response(
        raw=raw,
        tool_name=_TOOL_WORKNET_JOB,
        source_id=source_id,
        params=params,
        secret_keys=("authKey",),
        summary_text=(
            f"워크넷 채용 검색 keyword='{input.keyword or '(전체)'}' "
            f"(page={input.start_page}, display={input.display}) "
            f"응답 {len(raw.content or '')}자 수신."
        ),
    )


__all__ = [
    "SearchPublicJobInput",
    "SearchWorknetJobInput",
    "search_public_job",
    "search_worknet_job",
]
