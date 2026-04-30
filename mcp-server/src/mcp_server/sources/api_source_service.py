"""공식 공공 API 호출 서비스 레이어.

규약:
- 도구는 직접 httpx 를 쓰지 말고 이 fetch() 만 호출한다.

캐시 구조 (도메인 단위 1 row):
- api_cache 는 tool_name 당 1 row. site_url 은 placeholder (cache://{tool_name}).
- fetch 성공 시 해당 row 를 upsert (덮어쓰기).
- 외부 호출 실패 + 캐시 있음 → 캐시 반환 (fetched_at=cache.cached_at).
- 외부 호출 실패 + 캐시 없음 → 빈 RawResult + fetch_error 메타.
"""

import json
from datetime import UTC, datetime
from urllib.parse import urlencode

import httpx
from sqlalchemy import select

from mcp_server.db.models import ApiCache, ApiSource
from mcp_server.db.session import get_session
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult


async def fetch(
    source_id: int,
    params: dict[str, str | int | float | bool] | None = None,
    *,
    _test_http_client: httpx.AsyncClient | None = None,
) -> RawResult:
    """등록된 api_source 1건을 호출하고 결과를 RawResult 로 반환.

    Args:
        source_id: api_source.id
        params: 쿼리 파라미터 (api_source.endpoint 에 GET 으로 부착)
        _test_http_client: **테스트 전용** httpx MockTransport 주입 hook.
            도구 코드에서 절대 사용 금지 — "외부 호출은 서비스 레이어 경유" 규약 위반.
            None 이면 함수 내부에서 일회용 클라이언트 생성.

    Raises:
        SourceNotFoundError: 등록되지 않은 source_id.
        SourceFetchError: HTTP 호출 실패 (네트워크 오류 / 4xx-5xx 응답).
    """
    source = await _load_source(source_id)
    params = params or {}
    fetched_at = datetime.now(UTC)

    try:
        response_text = await _call_external_api(
            endpoint=source.url_template,
            params=params,
            http_client=_test_http_client,
        )
    except SourceFetchError as exc:
        cached = await _load_cache_by_tool(source.tool_name)
        base_meta = {
            "tool_name": source.tool_name,
            "url_template": source.url_template,
            "params": params,
        }
        if cached is not None:
            return RawResult(
                source_type="api",
                source_id=source.id,
                content=cached.content or "",
                fetched_at=cached.cached_at,
                raw_metadata=base_meta,
            )
        return RawResult(
            source_type="api",
            source_id=source.id,
            content="",
            fetched_at=fetched_at,
            raw_metadata={**base_meta, "fetch_error": str(exc)},
        )

    await _upsert_cache_by_tool(
        source_id=source.id,
        tool_name=source.tool_name,
        content=response_text,
        cached_at=fetched_at,
    )

    return RawResult(
        source_type="api",
        source_id=source.id,
        content=response_text,
        fetched_at=fetched_at,
        raw_metadata={
            "tool_name": source.tool_name,
            "url_template": source.url_template,
            "params": params or {},
        },
    )


def _build_site_url(url_template: str, params: dict) -> str:
    """원본 호출 URL 디버그 표시용. 캐시 키로는 더 이상 사용하지 않음 (tool_name 으로 변경)."""
    cache_params = {k: v for k, v in params.items() if k != "serviceKey"}
    return f"{url_template}?{urlencode(sorted(cache_params.items()))}"


def _cache_site_url(tool_name: str) -> str:
    """캐시 site_url placeholder. UNIQUE 제약을 만족시키는 도메인 단위 1 row 키."""
    return f"cache://{tool_name}"


# 캐시 read 가 의미 있는 도구 화이트리스트.
# 부동산 6종은 LAWD_CD 가 API 필수라 도메인 단위 단일 호출이 불가능하고, tool_name
# 단위 1 row 캐시는 마지막 (region, deal_ymd) 응답만 보관하므로 다른 (region, ymd)
# 호출자에게 잘못된 응답을 줄 수 있다. 따라서 부동산 도구는 캐시 read 노출 대상에서
# 제외 — 캐시 팀의 check_api_cache 도구도 이 화이트리스트만 받아야 한다.
_CACHEABLE_TOOLS: frozenset[str] = frozenset({
    "search_public_job",
    "search_worknet_job",
    "search_law_info",
    "search_bill_info",
    "search_g2b_bid",
})


async def peek_cached_content(tool_name: str) -> tuple[str, datetime] | None:
    """캐시 팀의 check_api_cache 도구가 사용할 read 헬퍼.

    캐시에 저장된 외부 API 응답 원문(content) 과 저장 시각(cached_at) 을 반환.
    정규화·필터링은 도구 레이어 책임이라 여기선 raw content 그대로 노출한다.

    Args:
        tool_name: 캐시 read 대상 도구 이름.

    Returns:
        (content, cached_at) — 캐시가 있고 읽을 수 있을 때.
        None — 캐시가 없거나, tool_name 이 _CACHEABLE_TOOLS 에 없을 때.
    """
    if tool_name not in _CACHEABLE_TOOLS:
        return None
    cached = await _load_cache_by_tool(tool_name)
    if cached is None:
        return None
    return cached.content or "", cached.cached_at


async def _load_source(source_id: int) -> ApiSource:
    async with get_session() as session:
        result = await session.execute(select(ApiSource).where(ApiSource.id == source_id))
        source = result.scalar_one_or_none()
    if source is None:
        raise SourceNotFoundError(f"api_source.id={source_id} 등록되지 않음")
    return source


async def _load_cache_by_tool(tool_name: str) -> ApiCache | None:
    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.api_type == tool_name)
        )
        return result.scalar_one_or_none()


async def _upsert_cache_by_tool(
    source_id: int,
    tool_name: str,
    content: str,
    cached_at: datetime,
) -> None:
    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.api_type == tool_name)
        )
        cache = result.scalar_one_or_none()
        if cache is None:
            session.add(ApiCache(
                source_id=source_id,
                site_url=_cache_site_url(tool_name),
                api_type=tool_name,
                content=content,
                cached_at=cached_at,
                expired_at=None,
            ))
        else:
            cache.content = content
            cache.cached_at = cached_at
        await session.commit()


async def resolve_source_id_by_tool_name(tool_name: str) -> int:
    """tool_name 으로 등록된 api_source.id 를 조회한다.

    도구 모듈은 자기 source_id 를 코드에 박지 않고 이 함수로 lookup 해야 한다.
    seed 스크립트가 멱등 upsert 를 하더라도 테스트 환경(in-memory)에서는 매번 새 id 가 부여되므로
    하드코딩 금지.

    Raises:
        SourceNotFoundError: 해당 tool_name 으로 등록된 row 없음.
    """
    async with get_session() as session:
        result = await session.execute(
            select(ApiSource.id).where(ApiSource.tool_name == tool_name)
        )
        source_id = result.scalar_one_or_none()
    if source_id is None:
        raise SourceNotFoundError(f"api_source.tool_name={tool_name} 등록되지 않음")
    return source_id


async def _call_external_api(
    endpoint: str,
    params: dict[str, str | int | float | bool],
    http_client: httpx.AsyncClient | None,
) -> str:
    """url_template + params 로 GET. JSON 응답은 정렬·직렬화, 그 외는 .text 그대로."""
    try:
        if http_client is None:
            async with httpx.AsyncClient(timeout=10.0) as client:
                response = await client.get(endpoint, params=params)
        else:
            response = await http_client.get(endpoint, params=params)
        response.raise_for_status()
    except httpx.HTTPError as exc:
        raise SourceFetchError(f"API 호출 실패: {endpoint} ({exc})") from exc

    content_type = response.headers.get("content-type", "")
    if "application/json" in content_type:
        return json.dumps(response.json(), ensure_ascii=False, sort_keys=True)
    return response.text


__all__ = ["fetch", "peek_cached_content", "resolve_source_id_by_tool_name"]
