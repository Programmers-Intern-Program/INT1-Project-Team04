"""api_source_service.fetch() 단위 테스트.

외부 호출은 httpx.MockTransport 로 모킹. 실제 네트워크는 사용하지 않음.
"""

import json

import httpx
import pytest

from sqlalchemy import select

from mcp_server.db.models import ApiCache, ApiSource
from mcp_server.db.session import get_session
from mcp_server.sources import api_source_service
from mcp_server.sources.errors import SourceNotFoundError


async def _create_source(**overrides) -> ApiSource:
    defaults = {
        "tool_name": "search_house_price",
        "name": "국토부 아파트 매매 실거래가",
        "url_template": "https://example.gov/api/trade",
        "param_schema": {"type": "object"},
    }
    defaults.update(overrides)
    async with get_session() as session:
        source = ApiSource(**defaults)
        session.add(source)
        await session.commit()
        await session.refresh(source)
        return source


@pytest.mark.asyncio
async def test_fetch_returns_raw_result_with_json_body(patched_session_factory):
    """JSON 응답은 정렬된 문자열로 RawResult.content 에 들어간다."""
    source = await _create_source()

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/trade"
        assert request.url.params["region"] == "강남구"
        return httpx.Response(
            200,
            json={"items": [{"price": 1500}, {"price": 1700}]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        result = await api_source_service.fetch(
            source_id=source.id,
            params={"region": "강남구"},
            _test_http_client=client,
        )

    assert result.source_type == "api"
    assert result.source_id == source.id
    parsed = json.loads(result.content)
    assert parsed["items"][0]["price"] == 1500
    assert result.raw_metadata["tool_name"] == "search_house_price"
    assert result.raw_metadata["url_template"] == source.url_template
    assert result.raw_metadata["params"] == {"region": "강남구"}


@pytest.mark.asyncio
async def test_fetch_returns_text_for_non_json_response(patched_session_factory):
    """JSON 외 응답은 .text 그대로."""
    source = await _create_source(tool_name="xml_endpoint", url_template="https://example.gov/xml")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=b"<root><ok/></root>",
            headers={"content-type": "application/xml"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        result = await api_source_service.fetch(
            source_id=source.id, params=None, _test_http_client=client
        )

    assert "<ok/>" in result.content


@pytest.mark.asyncio
async def test_fetch_raises_source_not_found_for_unknown_id(patched_session_factory):
    """등록 안 된 source_id 는 SourceNotFoundError."""
    with pytest.raises(SourceNotFoundError):
        await api_source_service.fetch(source_id=9999, params={})


@pytest.mark.asyncio
async def test_fetch_returns_empty_result_on_http_error_without_cache(patched_session_factory):
    """HTTP 5xx + 캐시 없음 → 빈 content + fetch_error 기록."""
    source = await _create_source(
        tool_name="failing_endpoint", url_template="https://example.gov/fail"
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="Service Unavailable")

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        result = await api_source_service.fetch(
            source_id=source.id, params={}, _test_http_client=client
        )

    assert result.content == ""
    assert "fetch_error" in result.raw_metadata


@pytest.mark.asyncio
async def test_fetch_writes_cache_on_success_bulk_fetch(patched_session_factory):
    """bulk-fetch tool 성공 시 site_url = cache://{tool_name} 단일 행 upsert."""
    source = await _create_source(
        tool_name="search_law_info",
        url_template="https://example.gov/law",
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"items": [{"law": "민법"}]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        await api_source_service.fetch(source_id=source.id, params={}, _test_http_client=client)

    async with get_session() as session:
        result = await session.execute(select(ApiCache).where(ApiCache.source_id == source.id))
        cache = result.scalar_one_or_none()

    assert cache is not None
    assert cache.api_type == "search_law_info"
    assert cache.site_url == "cache://search_law_info"
    assert "민법" in cache.content


@pytest.mark.asyncio
async def test_fetch_writes_cache_on_success_param_keyed(patched_session_factory):
    """param-keyed tool 성공 시 site_url = cache://{tool_name}/{LAWD_CD}/{DEAL_YMD}."""
    source = await _create_source()  # search_house_price

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"items": [{"price": 1500}]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        await api_source_service.fetch(
            source_id=source.id,
            params={"LAWD_CD": "11680", "DEAL_YMD": "202403"},
            _test_http_client=client,
        )

    async with get_session() as session:
        result = await session.execute(select(ApiCache).where(ApiCache.source_id == source.id))
        cache = result.scalar_one_or_none()

    assert cache is not None
    assert cache.api_type == "search_house_price"
    assert cache.site_url == "cache://search_house_price/11680/202403"
    assert "1500" in cache.content


@pytest.mark.asyncio
async def test_fetch_overwrites_cache_for_bulk_fetch_tool(patched_session_factory):
    """bulk-fetch tool은 같은 tool_name 두 번 호출 시 1 row 유지(덮어쓰기)."""
    source = await _create_source(
        tool_name="search_law_info",
        url_template="https://example.gov/law",
    )
    counter = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        counter["n"] += 1
        return httpx.Response(
            200,
            json={"call": counter["n"]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        await api_source_service.fetch(source_id=source.id, params={}, _test_http_client=client)
        await api_source_service.fetch(source_id=source.id, params={}, _test_http_client=client)

    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.api_type == "search_law_info")
        )
        rows = result.scalars().all()

    assert len(rows) == 1
    assert '"call": 2' in rows[0].content


@pytest.mark.asyncio
async def test_fetch_creates_separate_rows_for_param_keyed_tool(patched_session_factory):
    """param-keyed tool은 (LAWD_CD, DEAL_YMD) 조합마다 별도 행 생성."""
    source = await _create_source()  # search_house_price
    call_no = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        call_no["n"] += 1
        return httpx.Response(
            200,
            json={"call": call_no["n"]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        await api_source_service.fetch(
            source_id=source.id,
            params={"LAWD_CD": "11680", "DEAL_YMD": "202403"},
            _test_http_client=client,
        )
        await api_source_service.fetch(
            source_id=source.id,
            params={"LAWD_CD": "11500", "DEAL_YMD": "202403"},  # 다른 지역
            _test_http_client=client,
        )

    async with get_session() as session:
        result = await session.execute(
            select(ApiCache).where(ApiCache.api_type == "search_house_price")
        )
        rows = result.scalars().all()

    assert len(rows) == 2
    site_urls = {r.site_url for r in rows}
    assert "cache://search_house_price/11680/202403" in site_urls
    assert "cache://search_house_price/11500/202403" in site_urls


@pytest.mark.asyncio
async def test_peek_cached_content_returns_none_when_no_cache(patched_session_factory):
    """캐시 row 가 없을 때 peek_cached_content 는 None."""
    await _create_source(tool_name="search_public_job", url_template="https://example.gov/alio")

    result = await api_source_service.peek_cached_content("search_public_job")

    assert result is None


@pytest.mark.asyncio
async def test_peek_cached_content_returns_content_and_cached_at(patched_session_factory):
    """캐시 채워진 후 peek_cached_content 는 (content, cached_at) 반환."""
    source = await _create_source(
        tool_name="search_public_job",
        url_template="https://example.gov/alio",
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"items": [{"title": "백엔드 개발자"}]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        await api_source_service.fetch(
            source_id=source.id, params={}, _test_http_client=client
        )

    result = await api_source_service.peek_cached_content("search_public_job")

    assert result is not None
    content, cached_at = result
    assert "백엔드 개발자" in content
    assert cached_at is not None


@pytest.mark.asyncio
async def test_peek_cached_content_requires_params_for_real_estate(patched_session_factory):
    """부동산은 params 포함 시 캐시 반환, params 없이 호출하면 None (다른 조합 오염 방지)."""
    source = await _create_source(
        tool_name="search_house_price",
        url_template="https://example.gov/molit/apt-trade",
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"items": [{"price": 1500}]},
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        await api_source_service.fetch(
            source_id=source.id,
            params={"LAWD_CD": "11680", "DEAL_YMD": "202403"},
            _test_http_client=client,
        )

    # 같은 params → 캐시 반환
    result = await api_source_service.peek_cached_content(
        "search_house_price", params={"LAWD_CD": "11680", "DEAL_YMD": "202403"}
    )
    assert result is not None
    content, _ = result
    assert "1500" in content

    # params 없이 호출 → site_url 불일치 → None
    result_no_params = await api_source_service.peek_cached_content("search_house_price")
    assert result_no_params is None

    # 다른 지역 params → site_url 불일치 → None
    result_diff = await api_source_service.peek_cached_content(
        "search_house_price", params={"LAWD_CD": "11500", "DEAL_YMD": "202403"}
    )
    assert result_diff is None


@pytest.mark.asyncio
async def test_fetch_returns_cache_on_http_error(patched_session_factory):
    """HTTP 5xx + 캐시 있음 → 캐시 반환, fetched_at 은 캐시 시각."""
    source = await _create_source()

    def ok_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"items": [{"price": 1500}]},
            headers={"content-type": "application/json"},
        )

    def fail_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="Service Unavailable")

    transport = httpx.MockTransport(ok_handler)
    async with httpx.AsyncClient(transport=transport) as client:
        first = await api_source_service.fetch(
            source_id=source.id,
            params={"region": "강남구"},
            _test_http_client=client,
        )

    transport = httpx.MockTransport(fail_handler)
    async with httpx.AsyncClient(transport=transport) as client:
        second = await api_source_service.fetch(
            source_id=source.id,
            params={"region": "강남구"},
            _test_http_client=client,
        )

    assert second.content == first.content
    assert second.fetched_at.replace(tzinfo=None) == first.fetched_at.replace(tzinfo=None)
    assert "fetch_error" not in second.raw_metadata
