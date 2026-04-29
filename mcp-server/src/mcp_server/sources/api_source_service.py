"""공식 공공 API 호출 서비스 레이어.

규약:
- 도구는 직접 httpx 를 쓰지 말고 이 fetch() 만 호출한다.

성공: API 호출 후 api_cache 에 upsert, RawResult 반환.
실패:
    캐시 있음 → RawResult(fetched_at=cache.cached_at) 반환.
    캐시 없음 → fetch_error 기록 후 빈 RawResult 반환.
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
    site_url = _build_site_url(source.url_template, params)
    fetched_at = datetime.now(UTC)

    try:
        response_text = await _call_external_api(
            endpoint=source.url_template,
            params=params,
            http_client=_test_http_client,
        )
    except SourceFetchError as exc:
        cached = await _load_cache(site_url)
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

    await _upsert_cache(
        source_id=source.id,
        site_url=site_url,
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
    """캐시 키용 URL. serviceKey 제외 (DB에 API 키 저장 방지)."""
    cache_params = {k: v for k, v in params.items() if k != "serviceKey"}
    return f"{url_template}?{urlencode(sorted(cache_params.items()))}"


async def _load_source(source_id: int) -> ApiSource:
    async with get_session() as session:
        result = await session.execute(select(ApiSource).where(ApiSource.id == source_id))
        source = result.scalar_one_or_none()
    if source is None:
        raise SourceNotFoundError(f"api_source.id={source_id} 등록되지 않음")
    return source


async def _load_cache(site_url: str) -> ApiCache | None:
    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.site_url == site_url)
        )
        return result.scalar_one_or_none()


async def _upsert_cache(
    source_id: int,
    site_url: str,
    tool_name: str,
    content: str,
    cached_at: datetime,
) -> None:
    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.site_url == site_url)
        )
        cache = result.scalar_one_or_none()
        if cache is None:
            session.add(ApiCache(
                source_id=source_id,
                site_url=site_url,
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


__all__ = ["fetch", "resolve_source_id_by_tool_name"]