"""crawl_source_service.fetch() 단위 테스트.

현재는 골격만 — 실제 렌더링은 추후 구현.
검증 항목:
1) source_id 로딩
2) 미구현 시 SourceFetchError 발생
3) is_active=False 인 source 호출 시 SourceFetchError
"""

import pytest

from mcp_server.db.models import CrawlSource
from mcp_server.db.session import get_session
from mcp_server.sources import crawl_source_service
from mcp_server.sources.errors import (
    SourceFetchError,
    SourceNotFoundError,
    SourceNotImplementedError,
)


async def _create_source(**overrides) -> CrawlSource:
    defaults = {
        "tool_name": "search_recruitment",
        "name": "사람인 채용 목록",
        "base_url": "https://www.example.com/list",
    }
    defaults.update(overrides)
    async with get_session() as session:
        source = CrawlSource(**defaults)
        session.add(source)
        await session.commit()
        await session.refresh(source)
        return source


@pytest.mark.asyncio
async def test_fetch_raises_source_not_found_for_unknown_id(patched_session_factory):
    with pytest.raises(SourceNotFoundError):
        await crawl_source_service.fetch(source_id=9999)


@pytest.mark.asyncio
async def test_fetch_currently_raises_source_not_implemented(patched_session_factory):
    """Phase 1 골격: 등록된 source 라도 렌더링 미구현 → SourceNotImplementedError.

    SourceFetchError 가 아닌 이유: 재시도 무의미한 영구 실패라 구분 필요.
    """
    source = await _create_source()
    with pytest.raises(SourceNotImplementedError):
        await crawl_source_service.fetch(source_id=source.id)


@pytest.mark.asyncio
async def test_fetch_rejects_inactive_source(patched_session_factory):
    """is_active=False 인 source 는 호출 시점에 거절."""
    source = await _create_source(is_active=False)
    with pytest.raises(SourceFetchError):
        await crawl_source_service.fetch(source_id=source.id)
