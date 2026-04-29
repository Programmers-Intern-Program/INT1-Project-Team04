"""tools.real_estate.* 단위 테스트.

실제 HTTP 호출 없이 api_source_service.fetch 를 stub 으로 교체해 도구 자체의 책임
(키 검증 → region 변환 → fetch → 정규화 → 통계 산출 → 공통 스키마 반환) 만 격리한다.

자료유형 5종 (search_house_price / search_apt_rent / search_offi_trade /
search_offi_rent / search_rh_rent) 모두 같은 fetch stub 패턴 공유.
"""

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from mcp_server.config import get_settings
from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session
from mcp_server.domains.real_estate.errors import (
    RealEstateConfigError,
    RealEstateNormalizationError,
    RealEstateRegionNotFoundError,
)
from mcp_server.sources import api_source_service
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult
from mcp_server.tools import real_estate
from mcp_server.tools.real_estate import (
    MolitRealEstateInput,
    SearchHousePriceInput,
    search_apt_rent,
    search_house_price,
    search_offi_rent,
    search_offi_trade,
    search_rh_rent,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
SAMPLE_XML = (FIXTURES / "molit_apt_trade_sample.xml").read_text(encoding="utf-8")
EMPTY_XML = (FIXTURES / "molit_apt_trade_empty.xml").read_text(encoding="utf-8")
ERROR_XML = (FIXTURES / "molit_apt_trade_error.xml").read_text(encoding="utf-8")

# 신규 자료유형별 sample 픽스처
APT_RENT_XML = (FIXTURES / "molit_apt_rent_sample.xml").read_text(encoding="utf-8")
OFFI_TRADE_XML = (FIXTURES / "molit_offi_trade_sample.xml").read_text(encoding="utf-8")
OFFI_RENT_XML = (FIXTURES / "molit_offi_rent_sample.xml").read_text(encoding="utf-8")
RH_RENT_XML = (FIXTURES / "molit_rh_rent_sample.xml").read_text(encoding="utf-8")


@pytest.fixture(autouse=True)
def reset_module_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    """도구 모듈 source_id 캐시(dict) 를 테스트 간 격리."""
    monkeypatch.setattr(real_estate, "_cached_source_ids", {})


@pytest.fixture(autouse=True)
def set_api_keys(monkeypatch: pytest.MonkeyPatch) -> None:
    """5종 자료유형 키 모두 기본 주입. 키 미설정 분기 테스트는 자기 안에서 다시 비운다."""
    monkeypatch.setenv("MOLIT_APT_TRADE_API_KEY", "test-key-12345")
    monkeypatch.setenv("MOLIT_APT_RENT_API_KEY", "test-key-12345")
    monkeypatch.setenv("MOLIT_OFFI_TRADE_API_KEY", "test-key-12345")
    monkeypatch.setenv("MOLIT_OFFI_RENT_API_KEY", "test-key-12345")
    monkeypatch.setenv("MOLIT_RH_RENT_API_KEY", "test-key-12345")
    get_settings.cache_clear()


async def _seed_source(
    tool_name: str = "search_house_price",
    url_template: str = "https://example.gov/molit/apt-trade",
    name: str = "국토교통부 아파트 매매 실거래가 (테스트)",
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
    tool_name: str = "search_house_price",
    url_template: str = "https://example.gov/molit/apt-trade",
):
    """fetch 호출을 가로채 params 를 captured 에 기록하고 RawResult 를 반환하는 fake."""

    async def fake(source_id: int, params: dict | None = None, **_: Any) -> RawResult:
        captured["source_id"] = source_id
        captured["params"] = params or {}
        return RawResult(
            source_type="api",
            source_id=source_id,
            content=content,
            fetched_at=datetime(2026, 4, 24, 12, 0, tzinfo=UTC),
            raw_metadata={
                "tool_name": tool_name,
                "url_template": url_template,
                "params": params or {},
            },
        )

    return fake


def _build_xml_with_n_items(n: int) -> str:
    items = "\n".join(
        f"""
        <item>
          <aptNm>아파트{i}</aptNm>
          <dealAmount>{(i + 1) * 1000}</dealAmount>
          <dealYear>2024</dealYear>
          <dealMonth>3</dealMonth>
          <dealDay>{(i % 28) + 1}</dealDay>
          <excluUseAr>59.99</excluUseAr>
          <floor>{(i % 20) + 1}</floor>
          <umdNm>역삼동</umdNm>
          <jibun>123</jibun>
          <buildYear>2010</buildYear>
        </item>"""
        for i in range(n)
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<response>
  <header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header>
  <body>
    <items>{items}</items>
    <numOfRows>1000</numOfRows>
    <pageNo>1</pageNo>
    <totalCount>{n}</totalCount>
  </body>
</response>"""


@pytest.mark.asyncio
async def test_search_returns_common_schema_on_normal_response(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """sample.xml fake → 공통 스키마 dict 반환, 모든 필수 키 존재."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_XML, captured))

    result = await search_house_price(
        SearchHousePriceInput(region="강남구", deal_ymd="202403")
    )

    assert isinstance(result["text"], str) and result["text"]
    assert result["structured"]["summary"]["count"] == 2
    assert len(result["structured"]["trades"]) == 2
    assert result["structured"]["trades_truncated"] is False
    assert result["structured"]["query"] == {
        "region": "강남구",
        "lawd_cd": "11680",
        "deal_ymd": "202403",
    }
    assert "serviceKey=***" in result["source_url"]
    assert result["metadata"]["raw_count"] == 2
    assert result["metadata"]["returned_count"] == 2
    assert result["metadata"]["tool_name"] == "search_house_price"
    assert result["metadata"]["fetched_at"]


@pytest.mark.asyncio
async def test_search_passes_resolved_lawd_cd_to_fetch_params(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """region 자연어가 LAWD_CD 로 변환돼 fetch params 에 전달되는지 검증."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_XML, captured))

    await search_house_price(SearchHousePriceInput(region="강남구", deal_ymd="202403"))

    assert captured["params"]["LAWD_CD"] == "11680"
    assert captured["params"]["DEAL_YMD"] == "202403"
    assert captured["params"]["serviceKey"] == "test-key-12345"


@pytest.mark.asyncio
async def test_search_handles_empty_response(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """빈 응답 → count=0, trades=[], text 가 '0건' 명시."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(EMPTY_XML, captured))

    result = await search_house_price(
        SearchHousePriceInput(region="11680", deal_ymd="202403")
    )

    assert result["structured"]["summary"]["count"] == 0
    assert result["structured"]["summary"]["avg_deal_amount"] is None
    assert result["structured"]["trades"] == []
    assert "0건" in result["text"]


@pytest.mark.asyncio
async def test_search_propagates_normalization_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """API 에러 응답(error.xml) → RealEstateNormalizationError."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(ERROR_XML, captured))

    with pytest.raises(RealEstateNormalizationError):
        await search_house_price(
            SearchHousePriceInput(region="11680", deal_ymd="202403")
        )


@pytest.mark.asyncio
async def test_search_raises_when_api_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """MOLIT_APT_TRADE_API_KEY 빈 값 → RealEstateConfigError, fetch 호출 X."""
    await _seed_source()
    monkeypatch.setenv("MOLIT_APT_TRADE_API_KEY", "")
    get_settings.cache_clear()

    fetch_called = False

    async def fail_fetch(*_args, **_kwargs):
        nonlocal fetch_called
        fetch_called = True
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(RealEstateConfigError):
        await search_house_price(
            SearchHousePriceInput(region="11680", deal_ymd="202403")
        )
    assert fetch_called is False


@pytest.mark.asyncio
async def test_search_raises_homonym_region_error_without_calling_fetch(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """region 동음이의 → RealEstateRegionNotFoundError. fetch 미호출."""
    await _seed_source()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(RealEstateRegionNotFoundError) as excinfo:
        await search_house_price(
            SearchHousePriceInput(region="중구", deal_ymd="202403")
        )
    assert excinfo.value.candidates


@pytest.mark.asyncio
async def test_search_propagates_source_not_found_when_seed_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """api_source 시드 미실행 → SourceNotFoundError 전파."""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_XML, captured))

    with pytest.raises(SourceNotFoundError):
        await search_house_price(
            SearchHousePriceInput(region="11680", deal_ymd="202403")
        )


@pytest.mark.asyncio
async def test_search_truncates_trades_to_top_n(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """25건 응답 → trades 상위 20건 절단, summary.count 는 원본 그대로."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(_build_xml_with_n_items(25), captured),
    )

    result = await search_house_price(
        SearchHousePriceInput(region="11680", deal_ymd="202403")
    )

    assert result["structured"]["summary"]["count"] == 25
    assert len(result["structured"]["trades"]) == 20
    assert result["structured"]["trades_truncated"] is True
    assert result["metadata"]["raw_count"] == 25
    assert result["metadata"]["returned_count"] == 20
    # 가격 내림차순 정렬 검증
    amounts = [t["deal_amount"] for t in result["structured"]["trades"]]
    assert amounts == sorted(amounts, reverse=True)


@pytest.mark.asyncio
async def test_search_propagates_fetch_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """fetch 가 SourceFetchError 를 던지면 그대로 전파."""
    await _seed_source()

    async def boom(*_args, **_kwargs):
        raise SourceFetchError("upstream 503")

    monkeypatch.setattr(api_source_service, "fetch", boom)

    with pytest.raises(SourceFetchError):
        await search_house_price(
            SearchHousePriceInput(region="11680", deal_ymd="202403")
        )


def test_input_schema_does_not_expose_service_key():
    """입력 모델 필드에 serviceKey 가 노출되지 않는지 정적 검증.

    @traced 가 입력을 자동 캡처하므로 비밀값이 인자로 들어오면 Langfuse 평문 노출 위험.
    """
    fields = SearchHousePriceInput.model_fields
    assert "serviceKey" not in fields
    assert "service_key" not in fields
    assert set(fields.keys()) == {"region", "deal_ymd"}


def test_input_validates_deal_ymd_pattern():
    """deal_ymd 는 6자리 숫자만 허용."""
    with pytest.raises(Exception):
        SearchHousePriceInput(region="강남구", deal_ymd="2024-03")
    with pytest.raises(Exception):
        SearchHousePriceInput(region="강남구", deal_ymd="20240")


def test_search_house_price_input_alias():
    """SearchHousePriceInput 은 MolitRealEstateInput 의 호환 alias."""
    assert SearchHousePriceInput is MolitRealEstateInput


# ─────────────────────────────────────────────
# search_apt_rent (아파트 전월세)
# ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_search_apt_rent_returns_rent_summary(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """전월세 정상 응답 → summary 에 deposit/monthly_rent 통계, trades 에 apt_name."""
    await _seed_source(
        tool_name="search_apt_rent",
        url_template="https://example.gov/molit/apt-rent",
        name="국토교통부 아파트 전월세 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            APT_RENT_XML,
            captured,
            tool_name="search_apt_rent",
            url_template="https://example.gov/molit/apt-rent",
        ),
    )

    result = await search_apt_rent(MolitRealEstateInput(region="강남구", deal_ymd="202403"))

    summary = result["structured"]["summary"]
    assert summary["count"] == 2
    assert summary["avg_deposit"] == 45000  # (70000 + 20000) / 2
    assert summary["min_deposit"] == 20000
    assert summary["max_deposit"] == 70000
    assert summary["avg_monthly_rent"] == 60  # (0 + 120) / 2
    # 보증금 내림차순
    deposits = [t["deposit"] for t in result["structured"]["trades"]]
    assert deposits == sorted(deposits, reverse=True)
    # apt_name 필드가 그대로 노출
    assert "apt_name" in result["structured"]["trades"][0]
    assert result["metadata"]["tool_name"] == "search_apt_rent"


@pytest.mark.asyncio
async def test_search_apt_rent_raises_when_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """MOLIT_APT_RENT_API_KEY 빈 값 → RealEstateConfigError, fetch 미호출."""
    await _seed_source(tool_name="search_apt_rent")
    monkeypatch.setenv("MOLIT_APT_RENT_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(RealEstateConfigError):
        await search_apt_rent(MolitRealEstateInput(region="11680", deal_ymd="202403"))


# ─────────────────────────────────────────────
# search_offi_trade (오피스텔 매매)
# ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_search_offi_trade_returns_trade_summary(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """오피스텔 매매 정상 응답 → summary 에 deal_amount 통계, trades 에 offi_name."""
    await _seed_source(
        tool_name="search_offi_trade",
        url_template="https://example.gov/molit/offi-trade",
        name="국토교통부 오피스텔 매매 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            OFFI_TRADE_XML,
            captured,
            tool_name="search_offi_trade",
            url_template="https://example.gov/molit/offi-trade",
        ),
    )

    result = await search_offi_trade(MolitRealEstateInput(region="11680", deal_ymd="202403"))

    summary = result["structured"]["summary"]
    assert summary["count"] == 2
    assert summary["avg_deal_amount"] == 21250  # (27500 + 15000) / 2
    assert summary["max_deal_amount"] == 27500
    # offi_name 필드 노출 (apt_name 아님)
    first = result["structured"]["trades"][0]
    assert "offi_name" in first
    assert "apt_name" not in first
    assert result["metadata"]["tool_name"] == "search_offi_trade"


@pytest.mark.asyncio
async def test_search_offi_trade_raises_when_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    await _seed_source(tool_name="search_offi_trade")
    monkeypatch.setenv("MOLIT_OFFI_TRADE_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(RealEstateConfigError):
        await search_offi_trade(MolitRealEstateInput(region="11680", deal_ymd="202403"))


# ─────────────────────────────────────────────
# search_offi_rent (오피스텔 전월세)
# ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_search_offi_rent_returns_rent_summary(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    await _seed_source(
        tool_name="search_offi_rent",
        url_template="https://example.gov/molit/offi-rent",
        name="국토교통부 오피스텔 전월세 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            OFFI_RENT_XML,
            captured,
            tool_name="search_offi_rent",
            url_template="https://example.gov/molit/offi-rent",
        ),
    )

    result = await search_offi_rent(MolitRealEstateInput(region="11680", deal_ymd="202403"))

    summary = result["structured"]["summary"]
    assert summary["count"] == 2
    assert summary["avg_deposit"] == 10750  # (16500 + 5000) / 2
    assert summary["avg_monthly_rent"] == 45  # (10 + 80) / 2
    first = result["structured"]["trades"][0]
    assert "offi_name" in first
    assert result["metadata"]["tool_name"] == "search_offi_rent"


@pytest.mark.asyncio
async def test_search_offi_rent_raises_when_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    await _seed_source(tool_name="search_offi_rent")
    monkeypatch.setenv("MOLIT_OFFI_RENT_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(RealEstateConfigError):
        await search_offi_rent(MolitRealEstateInput(region="11680", deal_ymd="202403"))


# ─────────────────────────────────────────────
# search_rh_rent (연립다세대 전월세)
# ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_search_rh_rent_returns_rent_summary_with_house_type(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """연립다세대 응답 → summary 는 전월세 공통, trades 는 mhouse_name + house_type."""
    await _seed_source(
        tool_name="search_rh_rent",
        url_template="https://example.gov/molit/rh-rent",
        name="국토교통부 연립다세대 전월세 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            RH_RENT_XML,
            captured,
            tool_name="search_rh_rent",
            url_template="https://example.gov/molit/rh-rent",
        ),
    )

    result = await search_rh_rent(MolitRealEstateInput(region="11680", deal_ymd="202403"))

    summary = result["structured"]["summary"]
    assert summary["count"] == 2
    assert summary["avg_deposit"] == 20250  # (30500 + 10000) / 2
    first = result["structured"]["trades"][0]
    assert "mhouse_name" in first
    assert "house_type" in first
    assert first["house_type"] in {"다세대", "연립"}
    assert result["metadata"]["tool_name"] == "search_rh_rent"


@pytest.mark.asyncio
async def test_search_rh_rent_raises_when_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    await _seed_source(tool_name="search_rh_rent")
    monkeypatch.setenv("MOLIT_RH_RENT_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(RealEstateConfigError):
        await search_rh_rent(MolitRealEstateInput(region="11680", deal_ymd="202403"))


# ─────────────────────────────────────────────
# tool_name 별 source_id 캐시 격리 검증
# ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_resolve_source_id_caches_per_tool_name(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """5개 도구가 같은 _cached_source_ids dict 를 공유하지만 키 충돌 없이 각자 캐시."""
    apt_trade_row = await _seed_source(tool_name="search_house_price")
    offi_trade_row = await _seed_source(
        tool_name="search_offi_trade",
        url_template="https://example.gov/molit/offi-trade",
        name="오피스텔 매매 (테스트)",
    )

    captured1: dict[str, Any] = {}
    captured2: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(SAMPLE_XML, captured1),
    )
    await search_house_price(MolitRealEstateInput(region="11680", deal_ymd="202403"))

    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            OFFI_TRADE_XML,
            captured2,
            tool_name="search_offi_trade",
            url_template="https://example.gov/molit/offi-trade",
        ),
    )
    await search_offi_trade(MolitRealEstateInput(region="11680", deal_ymd="202403"))

    # 각 도구별로 자기 source_id 가 캐시됐는지 검증
    assert real_estate._cached_source_ids["search_house_price"] == apt_trade_row.id
    assert real_estate._cached_source_ids["search_offi_trade"] == offi_trade_row.id
