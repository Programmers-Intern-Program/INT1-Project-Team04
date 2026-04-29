"""tools.law.* 단위 테스트.

실제 HTTP 호출 없이 api_source_service.fetch 를 stub 으로 교체해 도구 책임만 격리.
"""

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from mcp_server.config import get_settings
from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session
from mcp_server.domains.law.errors import LawConfigError, LawNormalizationError
from mcp_server.sources import api_source_service
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult
from mcp_server.tools import law
from mcp_server.tools.law import (
    SearchBillInfoInput,
    SearchLawInfoInput,
    search_bill_info,
    search_law_info,
)

FIXTURES = Path(__file__).resolve().parents[1] / "data" / "law"
LAW_INFO_XML = (FIXTURES / "search_law_info.xml").read_text(encoding="utf-8")
BILL_INFO_XML = (FIXTURES / "search_bill_info.xml").read_text(encoding="utf-8")


@pytest.fixture(autouse=True)
def reset_module_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(law, "_cached_source_ids", {})


@pytest.fixture(autouse=True)
def set_api_keys(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MOLEG_LAW_INFO_API_KEY", "test-law-key")
    monkeypatch.setenv("NA_BILL_INFO_API_KEY", "test-bill-key")
    get_settings.cache_clear()


async def _seed_source(
    tool_name: str,
    url_template: str,
    name: str,
) -> ApiSource:
    async with get_session() as session:
        row = ApiSource(
            tool_name=tool_name,
            name=name,
            url_template=url_template,
            param_schema={"type": "object"},
        )
        session.add(row)
        await session.commit()
        await session.refresh(row)
        return row


def _make_fake_fetch(
    content: str,
    captured: dict[str, Any],
    *,
    tool_name: str,
    url_template: str,
):
    async def fake(source_id: int, params: dict | None = None, **_: Any) -> RawResult:
        captured["source_id"] = source_id
        captured["params"] = params or {}
        return RawResult(
            source_type="api",
            source_id=source_id,
            content=content,
            fetched_at=datetime(2026, 4, 29, 12, 0, tzinfo=UTC),
            raw_metadata={
                "tool_name": tool_name,
                "url_template": url_template,
                "params": params or {},
            },
        )

    return fake


# ─────────────────────────────────────────────
# search_law_info
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_search_law_info_returns_common_schema(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """법제처 픽스처 → 공통 4필드 스키마 dict 반환."""
    await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처 국가법령정보 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            LAW_INFO_XML,
            captured,
            tool_name="search_law_info",
            url_template="https://example.gov/moleg/law-info",
        ),
    )

    result = await search_law_info(SearchLawInfoInput(query="개인정보 보호법", num_of_rows=5))

    assert isinstance(result["text"], str) and result["text"]
    assert result["structured"]["summary"]["count"] == 2
    assert len(result["structured"]["laws"]) == 2
    assert result["structured"]["laws_truncated"] is False
    assert "serviceKey=***" in result["source_url"]
    assert result["metadata"]["raw_count"] == 2
    assert result["metadata"]["tool_name"] == "search_law_info"


@pytest.mark.asyncio
async def test_search_law_info_passes_query_to_fetch_params(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """query/page_no/num_of_rows 가 fetch params 에 그대로 전달."""
    await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            LAW_INFO_XML,
            captured,
            tool_name="search_law_info",
            url_template="https://example.gov/moleg/law-info",
        ),
    )

    await search_law_info(SearchLawInfoInput(query="민법", page_no=2, num_of_rows=10))

    assert captured["params"]["query"] == "민법"
    assert captured["params"]["pageNo"] == 2
    assert captured["params"]["numOfRows"] == 10
    assert captured["params"]["target"] == "law"
    assert captured["params"]["serviceKey"] == "test-law-key"


@pytest.mark.asyncio
async def test_search_law_info_sorts_by_promulgation_date_desc(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """공포일자 내림차순 — 픽스처상 시행령(2025-09-23) 이 본법(2025-04-01) 보다 앞."""
    await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            LAW_INFO_XML,
            captured,
            tool_name="search_law_info",
            url_template="https://example.gov/moleg/law-info",
        ),
    )

    result = await search_law_info(SearchLawInfoInput(query="개인정보 보호법"))

    laws = result["structured"]["laws"]
    assert laws[0]["law_name"] == "개인정보 보호법 시행령"
    assert laws[1]["law_name"] == "개인정보 보호법"


@pytest.mark.asyncio
async def test_search_law_info_propagates_normalization_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """resultCode != '00' → LawNormalizationError."""
    await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처 (테스트)",
    )
    error_xml = """<?xml version="1.0"?>
<LawSearch><resultCode>99</resultCode><resultMsg>error</resultMsg></LawSearch>"""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            error_xml,
            captured,
            tool_name="search_law_info",
            url_template="https://example.gov/moleg/law-info",
        ),
    )

    with pytest.raises(LawNormalizationError):
        await search_law_info(SearchLawInfoInput(query="민법"))


@pytest.mark.asyncio
async def test_search_law_info_raises_when_api_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """MOLEG_LAW_INFO_API_KEY 빈 값 → LawConfigError, fetch 호출 X."""
    await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처 (테스트)",
    )
    monkeypatch.setenv("MOLEG_LAW_INFO_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(LawConfigError):
        await search_law_info(SearchLawInfoInput(query="민법"))


@pytest.mark.asyncio
async def test_search_law_info_propagates_source_not_found(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """api_source 시드 미실행 → SourceNotFoundError."""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            LAW_INFO_XML,
            captured,
            tool_name="search_law_info",
            url_template="https://example.gov/moleg/law-info",
        ),
    )

    with pytest.raises(SourceNotFoundError):
        await search_law_info(SearchLawInfoInput(query="민법"))


@pytest.mark.asyncio
async def test_search_law_info_propagates_fetch_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처 (테스트)",
    )

    async def boom(*_args, **_kwargs):
        raise SourceFetchError("upstream 503")

    monkeypatch.setattr(api_source_service, "fetch", boom)

    with pytest.raises(SourceFetchError):
        await search_law_info(SearchLawInfoInput(query="민법"))


def test_search_law_info_input_does_not_expose_secret_key():
    fields = SearchLawInfoInput.model_fields
    assert "serviceKey" not in fields
    assert "service_key" not in fields


# ─────────────────────────────────────────────
# search_bill_info
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_search_bill_info_returns_common_schema(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """의안 픽스처 → 공통 4필드 스키마 + bills 리스트."""
    await _seed_source(
        tool_name="search_bill_info",
        url_template="https://example.gov/na/bill-info",
        name="국회 의안정보 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            BILL_INFO_XML,
            captured,
            tool_name="search_bill_info",
            url_template="https://example.gov/na/bill-info",
        ),
    )

    result = await search_bill_info(SearchBillInfoInput(age=22, num_of_rows=5))

    assert isinstance(result["text"], str) and result["text"]
    assert result["structured"]["summary"]["count"] >= 1
    assert "bills" in result["structured"]
    assert "KEY=***" in result["source_url"]
    assert result["metadata"]["tool_name"] == "search_bill_info"


@pytest.mark.asyncio
async def test_search_bill_info_passes_age_and_paging_to_params(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """AGE/pIndex/pSize 가 fetch params 에 전달."""
    await _seed_source(
        tool_name="search_bill_info",
        url_template="https://example.gov/na/bill-info",
        name="국회 의안 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            BILL_INFO_XML,
            captured,
            tool_name="search_bill_info",
            url_template="https://example.gov/na/bill-info",
        ),
    )

    await search_bill_info(SearchBillInfoInput(age=21, page_no=3, num_of_rows=10))

    assert captured["params"]["AGE"] == 21
    assert captured["params"]["pIndex"] == 3
    assert captured["params"]["pSize"] == 10
    assert captured["params"]["Type"] == "xml"
    assert captured["params"]["KEY"] == "test-bill-key"


@pytest.mark.asyncio
async def test_search_bill_info_propagates_normalization_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """RESULT/CODE != 'INFO-000' → LawNormalizationError."""
    await _seed_source(
        tool_name="search_bill_info",
        url_template="https://example.gov/na/bill-info",
        name="국회 의안 (테스트)",
    )
    error_xml = """<?xml version="1.0"?>
<root><head><RESULT><CODE>INFO-200</CODE><MESSAGE>없음</MESSAGE></RESULT></head></root>"""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            error_xml,
            captured,
            tool_name="search_bill_info",
            url_template="https://example.gov/na/bill-info",
        ),
    )

    with pytest.raises(LawNormalizationError):
        await search_bill_info(SearchBillInfoInput(age=22))


@pytest.mark.asyncio
async def test_search_bill_info_raises_when_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """NA_BILL_INFO_API_KEY 빈 값 → LawConfigError."""
    await _seed_source(
        tool_name="search_bill_info",
        url_template="https://example.gov/na/bill-info",
        name="국회 의안 (테스트)",
    )
    monkeypatch.setenv("NA_BILL_INFO_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(LawConfigError):
        await search_bill_info(SearchBillInfoInput(age=22))


def test_search_bill_info_input_does_not_expose_secret_key():
    fields = SearchBillInfoInput.model_fields
    assert "KEY" not in fields
    assert "key" not in fields


# ─────────────────────────────────────────────
# 캐시 격리 — 두 도구가 같은 dict 공유하지만 키 충돌 없이
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_resolve_source_id_caches_per_tool_name(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    law_row = await _seed_source(
        tool_name="search_law_info",
        url_template="https://example.gov/moleg/law-info",
        name="법제처",
    )
    bill_row = await _seed_source(
        tool_name="search_bill_info",
        url_template="https://example.gov/na/bill-info",
        name="국회",
    )

    captured1: dict[str, Any] = {}
    captured2: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            LAW_INFO_XML,
            captured1,
            tool_name="search_law_info",
            url_template="https://example.gov/moleg/law-info",
        ),
    )
    await search_law_info(SearchLawInfoInput(query="민법"))

    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            BILL_INFO_XML,
            captured2,
            tool_name="search_bill_info",
            url_template="https://example.gov/na/bill-info",
        ),
    )
    await search_bill_info(SearchBillInfoInput(age=22))

    assert law._cached_source_ids["search_law_info"] == law_row.id
    assert law._cached_source_ids["search_bill_info"] == bill_row.id
