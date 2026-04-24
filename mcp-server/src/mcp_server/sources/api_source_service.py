"""공식 공공 API 호출 서비스 레이어.

규약:
- 도구는 직접 httpx 를 쓰지 말고 이 fetch() 만 호출한다.
- 캐시 로직 삽입 지점은 # TODO: cache 주석으로 표시. MVP 에서는 통과.
"""

import json
from datetime import UTC, datetime

import httpx
from sqlalchemy import select

from mcp_server.db.models import ApiSource
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
    # TODO: cache lookup — site_url(url_template + params) 로 api_cache 조회

    fetched_at = datetime.now(UTC)
    response_text = await _call_external_api(
        endpoint=source.url_template,
        params=params or {},
        http_client=_test_http_client,
    )

    # TODO: cache store — api_cache 에 (site_url, content, expired_at) 기록

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


async def _load_source(source_id: int) -> ApiSource:
    async with get_session() as session:
        result = await session.execute(select(ApiSource).where(ApiSource.id == source_id))
        source = result.scalar_one_or_none()
    if source is None:
        raise SourceNotFoundError(f"api_source.id={source_id} 등록되지 않음")
    return source


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
        # JSON 은 정렬해서 hash 일관성 확보 (캐시 도입 시 활용)
        return json.dumps(response.json(), ensure_ascii=False, sort_keys=True)
    return response.text


__all__ = ["fetch"]
