"""크롤링 호출 서비스 레이어 골격.

인터페이스만 확정. 실제 Playwright/HTTP 호출은 `crawl_page` 에서 구현.

규약:
- 도구는 직접 playwright/httpx 를 쓰지 말고 이 fetch() 만 호출한다.
- 캐시 로직 삽입 지점은 # TODO: cache 주석으로 표시.
"""

from datetime import UTC, datetime

from sqlalchemy import select

from mcp_server.db.models import CrawlSource
from mcp_server.db.session import get_session
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult


async def fetch(
    source_id: int,
    params: dict[str, str | int | float | bool] | None = None,
) -> RawResult:
    """등록된 crawl_source 1건을 호출하고 결과를 RawResult 로 반환.

    현재는 골격만 제공. render_mode 분기와 실제 호출은 추후 구현.

    Raises:
        SourceNotFoundError: 등록되지 않은 source_id.
        SourceFetchError: 미구현 render_mode 등.
    """
    source = await _load_source(source_id)
    if not source.is_active:
        raise SourceFetchError(f"crawl_source.id={source_id} 은 비활성(is_active=False) 상태")
    # TODO: cache lookup — url(base_url + params) 로 crawl_cache 조회

    fetched_at = datetime.now(UTC)
    content = await _render(source, params or {})

    # TODO: cache store — crawl_cache 에 (url, content, expired_at) 기록

    return RawResult(
        source_type="crawl",
        source_id=source.id,
        content=content,
        fetched_at=fetched_at,
        raw_metadata={
            "tool_name": source.tool_name,
            "base_url": source.base_url,
            "css_selector": source.css_selector,
            "params": params or {},
        },
    )


async def _load_source(source_id: int) -> CrawlSource:
    async with get_session() as session:
        result = await session.execute(select(CrawlSource).where(CrawlSource.id == source_id))
        source = result.scalar_one_or_none()
    if source is None:
        raise SourceNotFoundError(f"crawl_source.id={source_id} 등록되지 않음")
    return source


async def _render(
    source: CrawlSource,
    params: dict[str, str | int | float | bool],
) -> str:
    """Phase 3-3 에서 Playwright/httpx 호출 + headers 적용 구현 예정.

    당장은 NotImplemented 로 명시 — 도구 담당자가 실수로 호출하지 않도록.
    """
    raise SourceFetchError("crawl_source 호출은 Phase 3-3 (`crawl_page` 도구) 에서 구현 예정")


__all__ = ["fetch"]
