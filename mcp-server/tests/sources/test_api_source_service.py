"""api_source_service.fetch() 단위 테스트.

외부 호출은 httpx.MockTransport 로 모킹. 실제 네트워크는 사용하지 않음.
"""

import json

import httpx
import pytest

from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session
from mcp_server.sources import api_source_service
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError


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
            http_client=client,
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
            source_id=source.id, params=None, http_client=client
        )

    assert "<ok/>" in result.content


@pytest.mark.asyncio
async def test_fetch_raises_source_not_found_for_unknown_id(patched_session_factory):
    """등록 안 된 source_id 는 SourceNotFoundError."""
    with pytest.raises(SourceNotFoundError):
        await api_source_service.fetch(source_id=9999, params={})


@pytest.mark.asyncio
async def test_fetch_wraps_http_errors_in_source_fetch_error(patched_session_factory):
    """HTTP 5xx 는 SourceFetchError 로 감싼다."""
    source = await _create_source(
        tool_name="failing_endpoint", url_template="https://example.gov/fail"
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="Service Unavailable")

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        with pytest.raises(SourceFetchError):
            await api_source_service.fetch(source_id=source.id, params={}, http_client=client)
