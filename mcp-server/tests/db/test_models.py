"""SQLAlchemy 모델 정의 검증 (팀 확정 ERD 기준)."""

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from mcp_server.db.models import ApiCache, ApiSource, CrawlCache, CrawlSource


@pytest.mark.asyncio
async def test_metadata_creates_four_tables(in_memory_engine):
    """metadata.create_all 로 4개 테이블이 생성된다."""
    async with in_memory_engine.connect() as conn:
        names = await conn.run_sync(lambda sync_conn: inspect(sync_conn).get_table_names())
    assert set(names) == {"api_source", "crawl_source", "api_cache", "crawl_cache"}


@pytest.mark.asyncio
async def test_api_source_insert_and_select(patched_session_factory):
    """api_source CRUD + JSON param_schema 직렬화 확인."""
    from mcp_server.db.session import get_session

    async with get_session() as session:
        source = ApiSource(
            tool_name="search_house_price",
            name="국토부 아파트 매매 실거래가",
            url_template="https://example.gov/api/trade?lawd={region}&deal_ymd={period}",
            param_schema={
                "type": "object",
                "properties": {"region": {"type": "string"}},
            },
        )
        session.add(source)
        await session.commit()
        await session.refresh(source)

        assert source.id is not None
        assert source.param_schema["properties"]["region"]["type"] == "string"

    async with get_session() as session:
        result = await session.execute(
            select(ApiSource).where(ApiSource.tool_name == "search_house_price")
        )
        loaded = result.scalar_one()
        assert loaded.url_template.startswith("https://example.gov/api/trade")


@pytest.mark.asyncio
async def test_crawl_source_defaults(patched_session_factory):
    """crawl_source 기본값: is_active=True, headers/css_selector 는 nullable."""
    from mcp_server.db.session import get_session

    async with get_session() as session:
        source = CrawlSource(
            tool_name="search_recruitment",
            name="사람인 채용 목록",
            base_url="https://www.saramin.co.kr/list",
        )
        session.add(source)
        await session.commit()
        await session.refresh(source)

    assert source.is_active is True
    assert source.headers is None
    assert source.css_selector is None


@pytest.mark.asyncio
async def test_api_cache_uuid_pk_and_unique_site_url(patched_session_factory):
    """api_cache: UUID PK 자동 생성 + site_url UNIQUE."""
    from mcp_server.db.session import get_session

    now = datetime.now(UTC)

    async with get_session() as session:
        api_source = ApiSource(tool_name="t", name="n", url_template="https://a")
        session.add(api_source)
        await session.commit()
        await session.refresh(api_source)

        first = ApiCache(
            source_id=api_source.id,
            site_url="https://example.gov/api/trade?lawd=11680",
            api_type="real_estate",
            content='{"items":[]}',
            expired_at=now + timedelta(hours=1),
        )
        session.add(first)
        await session.commit()
        await session.refresh(first)
        assert len(first.id) == 36  # UUID4 문자열

    async with get_session() as session:
        dup = ApiCache(
            source_id=api_source.id,
            site_url="https://example.gov/api/trade?lawd=11680",  # 동일 URL
            api_type="real_estate",
            content="x",
        )
        session.add(dup)
        with pytest.raises(IntegrityError):
            await session.commit()


@pytest.mark.asyncio
async def test_crawl_cache_uuid_pk_and_fk_naming(patched_session_factory):
    """crawl_cache: UUID PK + crawl_source_id FK 컬럼명 검증."""
    from mcp_server.db.session import get_session

    async with get_session() as session:
        crawl_source = CrawlSource(tool_name="t", name="n", base_url="https://b")
        session.add(crawl_source)
        await session.commit()
        await session.refresh(crawl_source)

        row = CrawlCache(
            crawl_source_id=crawl_source.id,
            url="https://www.saramin.co.kr/list?cat=python",
            content="<html></html>",
        )
        session.add(row)
        await session.commit()
        await session.refresh(row)
        assert len(row.id) == 36
        assert row.crawl_source_id == crawl_source.id
