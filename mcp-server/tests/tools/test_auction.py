"""tools.auction.* 단위 테스트.

실제 HTTP 호출 없이 api_source_service.fetch 를 stub 으로 교체해 도구의 책임
(키 검증 → fetch → 정규화 → 통계 산출 → 공통 스키마 반환) 만 격리.
"""

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from mcp_server.config import get_settings
from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session
from mcp_server.domains.auction.errors import (
    AuctionConfigError,
    AuctionNormalizationError,
)
from mcp_server.sources import api_source_service
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult
from mcp_server.tools import auction
from mcp_server.tools.auction import SearchG2bBidInput, search_g2b_bid

FIXTURES = Path(__file__).resolve().parents[1] / "data" / "auction"
SAMPLE_JSON = (FIXTURES / "search_g2b_bid.json").read_text(encoding="utf-8")


@pytest.fixture(autouse=True)
def reset_module_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    """도구 모듈 source_id 캐시(dict) 를 테스트 간 격리."""
    monkeypatch.setattr(auction, "_cached_source_ids", {})


@pytest.fixture(autouse=True)
def set_api_keys(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PPS_G2B_BID_API_KEY", "test-key-12345")
    get_settings.cache_clear()


async def _seed_source(
    tool_name: str = "search_g2b_bid",
    url_template: str = "https://example.gov/g2b/bid",
    name: str = "조달청 나라장터 입찰공고 (테스트)",
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
    tool_name: str = "search_g2b_bid",
    url_template: str = "https://example.gov/g2b/bid",
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


def _wrap_items(items: list[dict]) -> str:
    return json.dumps(
        {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "정상"},
                "body": {
                    "items": items,
                    "numOfRows": len(items),
                    "pageNo": 1,
                    "totalCount": len(items),
                },
            }
        }
    )


@pytest.mark.asyncio
async def test_search_returns_common_schema(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """5건 픽스처 → 공통 4필드 스키마 dict 반환."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_JSON, captured))

    result = await search_g2b_bid(SearchG2bBidInput())

    assert isinstance(result["text"], str) and result["text"]
    assert result["structured"]["summary"]["count"] == 5
    assert len(result["structured"]["notices"]) == 5
    assert result["structured"]["notices_truncated"] is False
    assert "serviceKey=***" in result["source_url"]
    assert result["metadata"]["raw_count"] == 5
    assert result["metadata"]["returned_count"] == 5
    assert result["metadata"]["tool_name"] == "search_g2b_bid"
    assert result["metadata"]["fetched_at"]


@pytest.mark.asyncio
async def test_search_default_dates_applied(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """inqry_bgn_dt / inqry_end_dt 미지정 시 fetch params 에 12자리 일자 자동 주입."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_JSON, captured))

    await search_g2b_bid(SearchG2bBidInput())

    assert len(captured["params"]["inqryBgnDt"]) == 12
    assert len(captured["params"]["inqryEndDt"]) == 12
    assert captured["params"]["serviceKey"] == "test-key-12345"
    assert captured["params"]["type"] == "json"


@pytest.mark.asyncio
async def test_search_explicit_dates_passed_through(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """inqry_bgn_dt 명시 시 그 값이 params 에 그대로 전달."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_JSON, captured))

    await search_g2b_bid(
        SearchG2bBidInput(inqry_bgn_dt="202604010000", inqry_end_dt="202604292359")
    )

    assert captured["params"]["inqryBgnDt"] == "202604010000"
    assert captured["params"]["inqryEndDt"] == "202604292359"


@pytest.mark.asyncio
async def test_search_handles_empty_response(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """빈 응답 → count=0, notices=[], text 가 '0건' 명시."""
    await _seed_source()
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(_wrap_items([]), captured))

    result = await search_g2b_bid(SearchG2bBidInput())

    assert result["structured"]["summary"]["count"] == 0
    assert result["structured"]["summary"]["avg_estimated_price"] is None
    assert result["structured"]["notices"] == []
    assert "0건" in result["text"]


@pytest.mark.asyncio
async def test_search_propagates_normalization_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """API 비정상 응답(resultCode != '00') → AuctionNormalizationError."""
    await _seed_source()
    error_json = json.dumps(
        {
            "response": {
                "header": {"resultCode": "99", "resultMsg": "서비스 키 오류"},
                "body": {"items": [], "numOfRows": 0, "pageNo": 1, "totalCount": 0},
            }
        }
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(error_json, captured))

    with pytest.raises(AuctionNormalizationError):
        await search_g2b_bid(SearchG2bBidInput())


@pytest.mark.asyncio
async def test_search_raises_when_api_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """PPS_G2B_BID_API_KEY 빈 값 → AuctionConfigError, fetch 호출 X."""
    await _seed_source()
    monkeypatch.setenv("PPS_G2B_BID_API_KEY", "")
    get_settings.cache_clear()

    fetch_called = False

    async def fail_fetch(*_args, **_kwargs):
        nonlocal fetch_called
        fetch_called = True
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(AuctionConfigError):
        await search_g2b_bid(SearchG2bBidInput())
    assert fetch_called is False


@pytest.mark.asyncio
async def test_search_propagates_source_not_found_when_seed_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """api_source 시드 미실행 → SourceNotFoundError 전파."""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_JSON, captured))

    with pytest.raises(SourceNotFoundError):
        await search_g2b_bid(SearchG2bBidInput())


@pytest.mark.asyncio
async def test_search_truncates_notices_to_top_n(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """25건 응답 → notices 상위 20건 절단, summary.count 는 원본 25 유지."""
    await _seed_source()
    items = [
        {
            "bidNtceNo": f"R26BK{i:08d}",
            "bidNtceOrd": "000",
            "bidNtceNm": f"테스트 공고 {i}",
            "ntceInsttNm": "테스트 기관",
            "bidNtceDt": f"2026-04-{(i % 28) + 1:02d} 12:00:00",
            "presmptPrce": str((i + 1) * 1_000_000),
        }
        for i in range(25)
    ]
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service, "fetch", _make_fake_fetch(_wrap_items(items), captured)
    )

    result = await search_g2b_bid(SearchG2bBidInput())

    assert result["structured"]["summary"]["count"] == 25
    assert len(result["structured"]["notices"]) == 20
    assert result["structured"]["notices_truncated"] is True
    assert result["metadata"]["raw_count"] == 25
    assert result["metadata"]["returned_count"] == 20
    # 공고일시 내림차순 정렬 검증
    dates = [n["notice_date"] for n in result["structured"]["notices"]]
    assert dates == sorted(dates, reverse=True)


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
        await search_g2b_bid(SearchG2bBidInput())


def test_input_schema_does_not_expose_service_key():
    """입력 모델 필드에 serviceKey 가 노출되지 않는지 정적 검증.

    @traced 가 입력을 자동 캡처하므로 비밀값이 인자로 들어오면 Langfuse 평문 노출 위험.
    """
    fields = SearchG2bBidInput.model_fields
    assert "serviceKey" not in fields
    assert "service_key" not in fields


def test_input_validates_inqry_bgn_dt_pattern():
    """inqry_bgn_dt 는 12자리 숫자만 허용."""
    with pytest.raises(Exception):
        SearchG2bBidInput(inqry_bgn_dt="2026-04-22")
    with pytest.raises(Exception):
        SearchG2bBidInput(inqry_bgn_dt="20260422")
