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
import logging
from datetime import UTC, datetime
from urllib.parse import urlencode

import httpx

_log = logging.getLogger(__name__)
from sqlalchemy import select

from mcp_server.db.models import ApiCache, ApiSource
from mcp_server.db.session import get_session
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult

# 공유 httpx 클라이언트 (프로세스당 1개) — 매 fetch() 마다 새로 만들면 커넥션 풀·keep-alive 가
# 버려져 도메인 도구 호출 1회 = TCP+TLS 핸드셰이크 1회가 된다. lazy singleton 으로 재사용.
_http_client: httpx.AsyncClient | None = None
_HTTP_TIMEOUT = 10.0


def _get_http_client() -> httpx.AsyncClient:
    global _http_client
    if _http_client is None:
        _http_client = httpx.AsyncClient(
            timeout=_HTTP_TIMEOUT,
            limits=httpx.Limits(max_connections=100, max_keepalive_connections=20),
        )
    return _http_client


async def aclose_http_client() -> None:
    """서버 lifespan 종료 시 호출. 열린 커넥션 정리."""
    global _http_client
    if _http_client is not None:
        await _http_client.aclose()
    _http_client = None


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

    cache_site_url = _derive_cache_site_url(source.tool_name, params)

    try:
        response_text = await _call_external_api(
            endpoint=source.url_template,
            params=params,
            http_client=_test_http_client,
        )
    except SourceFetchError as exc:
        _log.warning("fetch 실패, 캐시 폴백 시도: source_id=%s error=%s", source_id, exc)
        cached = await _load_cache_by_site_url(cache_site_url)
        base_meta = {
            "tool_name": source.tool_name,
            "url_template": source.url_template,
            "params": params,
        }
        if cached is not None:
            _log.info("fetch 실패 → 캐시 폴백 - tool=%s cached_at=%s", source.tool_name, cached.cached_at)
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

    await _upsert_cache_by_site_url(
        source_id=source.id,
        tool_name=source.tool_name,
        site_url=cache_site_url,
        content=response_text,
        cached_at=fetched_at,
    )
    _log.info("fetch 성공 - tool=%s bytes=%d", source.tool_name, len(response_text))

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
    """원본 호출 URL 디버그 표시용."""
    cache_params = {k: v for k, v in params.items() if k != "serviceKey"}
    return f"{url_template}?{urlencode(sorted(cache_params.items()))}"


# param-keyed tool: API 필수 파라미터 조합이 캐시 키를 결정.
# 여기 없는 tool은 bulk-fetch → tool_name 단위 1행 캐시.
_PARAM_KEY_FIELDS: dict[str, tuple[str, ...]] = {
    "search_house_price": ("LAWD_CD", "DEAL_YMD"),
    "search_apt_rent":    ("LAWD_CD", "DEAL_YMD"),
    "search_offi_trade":  ("LAWD_CD", "DEAL_YMD"),
    "search_offi_rent":   ("LAWD_CD", "DEAL_YMD"),
    "search_rh_rent":     ("LAWD_CD", "DEAL_YMD"),
    "search_rh_trade":    ("LAWD_CD", "DEAL_YMD"),
    "search_bill_info":   ("AGE",),
}


def _derive_cache_site_url(tool_name: str, params: dict) -> str:
    """tool_name + params 로 캐시 site_url 생성.

    bulk-fetch tool → "cache://{tool_name}"
    param-keyed tool → "cache://{tool_name}/{v1}/{v2}"

    params 키는 대소문자 무관하게 매칭 (fetch는 대문자, check_api_cache는 소문자 허용).
    """
    key_fields = _PARAM_KEY_FIELDS.get(tool_name)
    if not key_fields:
        return f"cache://{tool_name}"
    normalized = {k.upper(): str(v) for k, v in params.items()}
    parts = [normalized.get(k, "") for k in key_fields]
    return "cache://" + tool_name + "/" + "/".join(parts)


# 모든 tool이 캐시 가능. param-keyed tool은 params 필수.
_CACHEABLE_TOOLS: frozenset[str] = frozenset({
    "search_law_info",
    "search_bill_info",
    "search_public_job",
    "search_worknet_job",
    "search_g2b_bid",
    "search_house_price",
    "search_apt_rent",
    "search_offi_trade",
    "search_offi_rent",
    "search_rh_rent",
    "search_rh_trade",
})


async def peek_cached_at(tool_name: str, params: dict | None = None) -> datetime | None:
    """check_api_cache 전용. cached_at만 반환.

    param-keyed tool(부동산·의안)은 params 필수.
    params 없이 호출하면 None 반환 (cache miss로 처리).
    """
    if tool_name not in _CACHEABLE_TOOLS:
        return None
    site_url = _derive_cache_site_url(tool_name, params or {})
    cached = await _load_cache_by_site_url(site_url)
    return cached.cached_at if cached is not None else None


async def peek_cached_content(tool_name: str, params: dict | None = None) -> tuple[str, datetime] | None:
    """get_cached_data 전용. raw content + cached_at 반환.

    param-keyed tool(부동산·의안)은 params 필수.
    params 없이 호출하면 None 반환 (cache miss로 처리).
    """
    if tool_name not in _CACHEABLE_TOOLS:
        return None
    site_url = _derive_cache_site_url(tool_name, params or {})
    cached = await _load_cache_by_site_url(site_url)
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


async def _load_cache_by_site_url(site_url: str) -> ApiCache | None:
    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.site_url == site_url)
        )
        return result.scalar_one_or_none()


async def _upsert_cache_by_site_url(
    source_id: int,
    tool_name: str,
    site_url: str,
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
        client = http_client if http_client is not None else _get_http_client()
        response = await client.get(endpoint, params=params)
        response.raise_for_status()
    except httpx.HTTPError as exc:
        _log.error("외부 API 호출 실패: endpoint=%s error=%s", endpoint, exc)
        raise SourceFetchError(f"API 호출 실패: {endpoint} ({exc})") from exc

    content_type = response.headers.get("content-type", "")
    if "application/json" in content_type:
        return json.dumps(response.json(), ensure_ascii=False, sort_keys=True)
    return response.text


__all__ = [
    "fetch",
    "peek_cached_at",
    "peek_cached_content",
    "resolve_source_id_by_tool_name",
    "aclose_http_client",
    "_PARAM_KEY_FIELDS",
    "_CACHEABLE_TOOLS",
]
