"""tools.cache_check 캐시 조회 MCP 도구 단위 테스트.

실제 HTTP 호출 없이 DB(in-memory SQLite)에 캐시 행을 직접 삽입해
check_api_cache / get_cached_data 의 동작을 격리 검증한다.
"""

from datetime import UTC, datetime
from pathlib import Path

import pytest

from mcp_server.db.models import ApiCache, ApiSource
from mcp_server.db.session import get_session
from mcp_server.tools.cache_check import check_api_cache, get_cached_data

_FIXTURE_LAW = Path(__file__).resolve().parents[1] / "data" / "law"
_FIXTURE_AUCTION = Path(__file__).resolve().parents[1] / "data" / "auction"
_FIXTURE_JOBS = Path(__file__).resolve().parents[1] / "data" / "jobs"
_FIXTURE_RE = Path(__file__).resolve().parents[1] / "fixtures"

LAW_INFO_XML = (_FIXTURE_LAW / "search_law_info.xml").read_text(encoding="utf-8")
BILL_INFO_XML = (_FIXTURE_LAW / "search_bill_info.xml").read_text(encoding="utf-8")
G2B_BID_JSON = (_FIXTURE_AUCTION / "search_g2b_bid.json").read_text(encoding="utf-8")
PUBLIC_JOB_JSON = (_FIXTURE_JOBS / "search_public_job.json").read_text(encoding="utf-8")
WORKNET_PERMISSION_DENIED_XML = (_FIXTURE_JOBS / "search_worknet_job.xml").read_text(encoding="utf-8")
APT_TRADE_XML = (_FIXTURE_RE / "molit_apt_trade_sample.xml").read_text(encoding="utf-8")

_CACHED_AT = datetime(2026, 4, 30, 10, 0, tzinfo=UTC)


async def _seed_cache(tool_name: str, content: str, site_url: str | None = None) -> None:
    """site_url 미지정 시 bulk-fetch 기본값 "cache://{tool_name}" 사용."""
    async with get_session() as session:
        source = ApiSource(
            tool_name=tool_name,
            name=f"{tool_name} (test)",
            url_template=f"https://example.gov/{tool_name}",
            param_schema={"type": "object"},
        )
        session.add(source)
        await session.flush()
        cache = ApiCache(
            source_id=source.id,
            site_url=site_url or f"cache://{tool_name}",
            api_type=tool_name,
            content=content,
            cached_at=_CACHED_AT,
        )
        session.add(cache)
        await session.commit()


# ─────────────────────────────────────────────
# 반환 스키마 — step 필드
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_step_is_always_1_on_miss(patched_session_factory):
    """캐시 없음 경로에서도 step=1."""
    result = await check_api_cache("search_law_info")
    assert result["step"] == 1


@pytest.mark.asyncio
async def test_step_is_always_1_on_hit(patched_session_factory):
    """캐시 있음 경로에서도 step=1."""
    await _seed_cache("search_law_info", LAW_INFO_XML)
    result = await check_api_cache("search_law_info")
    assert result["step"] == 1


# ─────────────────────────────────────────────
# 캐시 없음 (첫 fetch)
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_cache_miss_returns_null_fields(patched_session_factory):
    """캐시 행 없음 → last_fetched_at·cached_data 모두 null."""
    result = await check_api_cache("search_law_info")

    assert result["last_fetched_at"] is None
    assert result["cached_data"] is None


# ─────────────────────────────────────────────
# 캐시 있음 — last_fetched_at
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_cache_hit_returns_last_fetched_at(patched_session_factory):
    """캐시 행 있음 → last_fetched_at ISO8601 문자열."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await check_api_cache("search_law_info")

    assert result["last_fetched_at"] is not None
    assert result["last_fetched_at"].startswith("2026-04-30T10:00:00")


# ─────────────────────────────────────────────
# 캐시 있음 — cached_data 구조 검증 (도메인별)
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_cached_data_law_info_schema(patched_session_factory):
    """법령 캐시 → cached_data에 4-field 스키마 + metadata.cache_used: true."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await check_api_cache("search_law_info")
    data = result["cached_data"]

    assert data is not None
    assert "text" in data
    assert "structured" in data
    assert "source_url" in data
    assert "metadata" in data
    assert data["metadata"]["cache_used"] is True
    assert data["metadata"]["tool_name"] == "search_law_info"
    assert "laws" in data["structured"]


@pytest.mark.asyncio
async def test_cached_data_bill_info_schema(patched_session_factory):
    """의안 캐시 → cached_data.structured.bills 키."""
    await _seed_cache("search_bill_info", BILL_INFO_XML, site_url="cache://search_bill_info/22")

    result = await check_api_cache("search_bill_info", params={"age": 22})

    assert "bills" in result["cached_data"]["structured"]


@pytest.mark.asyncio
async def test_cached_data_public_job_schema(patched_session_factory):
    """공공 채용 캐시 → cached_data.structured.postings 키."""
    await _seed_cache("search_public_job", PUBLIC_JOB_JSON)

    result = await check_api_cache("search_public_job")

    assert "postings" in result["cached_data"]["structured"]


@pytest.mark.asyncio
async def test_cached_data_g2b_bid_schema(patched_session_factory):
    """경매 캐시 → cached_data.structured.notices 키."""
    await _seed_cache("search_g2b_bid", G2B_BID_JSON)

    result = await check_api_cache("search_g2b_bid")

    assert "notices" in result["cached_data"]["structured"]


@pytest.mark.asyncio
async def test_cached_data_worknet_permission_denied(patched_session_factory):
    """권한 거부 XML 캐시 → cached_data.metadata.api_status: permission_denied."""
    await _seed_cache("search_worknet_job", WORKNET_PERMISSION_DENIED_XML)

    result = await check_api_cache("search_worknet_job")
    data = result["cached_data"]

    assert data["metadata"]["api_status"] == "permission_denied"
    assert data["structured"]["summary"]["count"] == 0


@pytest.mark.asyncio
async def test_cached_data_source_url_is_none(patched_session_factory):
    """캐시 조회이므로 cached_data.source_url: null."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await check_api_cache("search_law_info")

    assert result["cached_data"]["source_url"] is None


# ─────────────────────────────────────────────
# param-keyed tool (부동산·의안)
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_param_keyed_tool_without_params_returns_null(patched_session_factory):
    """부동산 tool에 params 없이 호출 → last_fetched_at·cached_data null + message."""
    result = await check_api_cache("search_house_price")

    assert result["last_fetched_at"] is None
    assert result["cached_data"] is None
    assert "message" in result
    assert "LAWD_CD" in result["message"]


@pytest.mark.asyncio
async def test_real_estate_cache_hit_with_params(patched_session_factory):
    """부동산 캐시 hit → last_fetched_at 존재 + cached_data.structured.trades."""
    await _seed_cache(
        "search_house_price", APT_TRADE_XML,
        site_url="cache://search_house_price/11680/202403",
    )

    result = await check_api_cache(
        "search_house_price", params={"lawd_cd": "11680", "deal_ymd": "202403"}
    )

    assert result["last_fetched_at"] is not None
    assert "trades" in result["cached_data"]["structured"]
    assert result["cached_data"]["metadata"]["cache_used"] is True


@pytest.mark.asyncio
async def test_real_estate_different_region_is_miss(patched_session_factory):
    """강남(11680) 캐시 → 마포(11500) 조회 → last_fetched_at null."""
    await _seed_cache(
        "search_house_price", APT_TRADE_XML,
        site_url="cache://search_house_price/11680/202403",
    )

    result = await check_api_cache(
        "search_house_price", params={"lawd_cd": "11500", "deal_ymd": "202403"}
    )

    assert result["last_fetched_at"] is None
    assert result["cached_data"] is None


@pytest.mark.asyncio
async def test_bill_info_age_keyed(patched_session_factory):
    """search_bill_info는 AGE 단위 param-keyed. AGE=22 캐시 → AGE=21 조회 → miss."""
    await _seed_cache("search_bill_info", BILL_INFO_XML, site_url="cache://search_bill_info/22")

    hit = await check_api_cache("search_bill_info", params={"age": 22})
    miss = await check_api_cache("search_bill_info", params={"age": 21})

    assert hit["last_fetched_at"] is not None
    assert miss["last_fetched_at"] is None


# ─────────────────────────────────────────────
# get_cached_data MCP tool 노출 확인
# ─────────────────────────────────────────────


def test_get_cached_data_registered_as_mcp_tool():
    """AI 구독 실행 프롬프트가 호출하는 get_cached_data는 MCP tool로 노출된다."""
    import mcp_server.tools  # noqa: F401
    from mcp_server.server import mcp

    tool_names = [t.name for t in mcp._tool_manager.list_tools()]
    assert "get_cached_data" in tool_names
    assert "check_api_cache" in tool_names


@pytest.mark.asyncio
async def test_get_cached_data_returns_cached_public_job_payload(patched_session_factory):
    """공공채용 캐시 hit → MCP tool 응답으로 구조화 채용공고를 반환한다."""
    await _seed_cache("search_public_job", PUBLIC_JOB_JSON)

    result = await get_cached_data("search_public_job")

    assert result["metadata"]["cache_used"] is True
    assert result["metadata"]["tool_name"] == "search_public_job"
    assert "postings" in result["structured"]
