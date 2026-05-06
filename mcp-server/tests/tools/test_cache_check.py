"""tools.cache_check.* 단위 테스트.

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
PUBLIC_JOB_MIXED_JSON = """
{
  "result": [
    {
      "recrutPblntSn": 101,
      "recrutPbancTtl": "백엔드 개발자 채용",
      "instNm": "한국테스트공단",
      "ongoingYn": "Y",
      "pbancBgngYmd": "20260430",
      "pbancEndYmd": "20260510",
      "srcUrl": "https://public.example/jobs/101"
    },
    {
      "recrutPblntSn": 102,
      "recrutPbancTtl": "프론트엔드 개발자 채용",
      "instNm": "한국테스트공단",
      "ongoingYn": "Y",
      "pbancBgngYmd": "20260429",
      "pbancEndYmd": "20260509",
      "srcUrl": "https://public.example/jobs/102"
    },
    {
      "recrutPblntSn": 103,
      "recrutPbancTtl": "백엔드 플랫폼 엔지니어 모집",
      "instNm": "테스트진흥원",
      "ongoingYn": "N",
      "pbancBgngYmd": "20260428",
      "pbancEndYmd": "20260429",
      "srcUrl": "https://public.example/jobs/103"
    }
  ],
  "resultCode": 200,
  "resultMsg": "성공했습니다.",
  "totalCount": 3
}
"""
WORKNET_JOB_MIXED_XML = """<?xml version='1.0' encoding='UTF-8'?>
<wantedRoot>
  <pubJobs>
    <wanted>
      <wantedAuthNo>W001</wantedAuthNo>
      <wantedTitle>백엔드 서버 개발자</wantedTitle>
      <busplaName>테스트소프트</busplaName>
      <regionNm>서울</regionNm>
      <empTpNm>정규직</empTpNm>
      <regDt>20260430</regDt>
      <closeDt>20260530</closeDt>
      <wantedInfoUrl>https://work.example/jobs/W001</wantedInfoUrl>
    </wanted>
    <wanted>
      <wantedAuthNo>W002</wantedAuthNo>
      <wantedTitle>프론트엔드 개발자</wantedTitle>
      <busplaName>백엔드랩스</busplaName>
      <regionNm>서울</regionNm>
      <empTpNm>계약직</empTpNm>
      <regDt>20260429</regDt>
      <closeDt>20260529</closeDt>
      <wantedInfoUrl>https://work.example/jobs/W002</wantedInfoUrl>
    </wanted>
    <wanted>
      <wantedAuthNo>W003</wantedAuthNo>
      <wantedTitle>데이터 엔지니어</wantedTitle>
      <busplaName>테스트데이터</busplaName>
      <regionNm>부산</regionNm>
      <empTpNm>정규직</empTpNm>
      <regDt>20260428</regDt>
      <closeDt>20260528</closeDt>
      <wantedInfoUrl>https://work.example/jobs/W003</wantedInfoUrl>
    </wanted>
  </pubJobs>
</wantedRoot>
"""

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
# check_api_cache
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_check_cache_miss(patched_session_factory):
    """캐시 행 없음 → cache_hit: false, last_fetched_at: null."""
    result = await check_api_cache("search_law_info")

    assert result["cache_hit"] is False
    assert result["last_fetched_at"] is None
    assert result["tool_name"] == "search_law_info"


@pytest.mark.asyncio
async def test_check_cache_hit(patched_session_factory):
    """캐시 행 있음 → cache_hit: true, last_fetched_at ISO8601 문자열."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await check_api_cache("search_law_info")

    assert result["cache_hit"] is True
    # SQLite은 timezone 미저장 → naive isoformat 반환. 날짜/시각 값만 검증.
    assert result["last_fetched_at"].startswith("2026-04-30T10:00:00")
    assert result["tool_name"] == "search_law_info"


@pytest.mark.asyncio
async def test_check_non_cacheable_tool(patched_session_factory):
    """화이트리스트 외 도구(부동산) → cache_hit: false."""
    result = await check_api_cache("search_house_price")

    assert result["cache_hit"] is False
    assert result["last_fetched_at"] is None


# ─────────────────────────────────────────────
# get_cached_data — cache miss / 비지원 도구
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_cache_miss(patched_session_factory):
    """캐시 없음 → cache_hit: false + message."""
    result = await get_cached_data("search_law_info")

    assert result["cache_hit"] is False
    assert "message" in result
    assert result["tool_name"] == "search_law_info"


@pytest.mark.asyncio
async def test_get_non_cacheable_tool(patched_session_factory):
    """화이트리스트 외 도구 → cache_hit: false."""
    result = await get_cached_data("search_house_price")

    assert result["cache_hit"] is False


# ─────────────────────────────────────────────
# get_cached_data — 포맷 검증 (law_info)
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_cache_hit_law_info_format(patched_session_factory):
    """법령 캐시 → 4-field 스키마 + metadata.cache_used: true."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await get_cached_data("search_law_info")

    assert "text" in result
    assert "structured" in result
    assert "source_url" in result
    assert "metadata" in result

    structured = result["structured"]
    assert "summary" in structured
    assert "laws" in structured
    assert "laws_truncated" in structured
    assert "query" in structured
    assert isinstance(structured["laws"], list)


@pytest.mark.asyncio
async def test_get_cache_used_flag(patched_session_factory):
    """metadata.cache_used: true 확인."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await get_cached_data("search_law_info")

    assert result["metadata"]["cache_used"] is True
    assert result["metadata"]["tool_name"] == "search_law_info"
    # SQLite은 timezone 미저장 → naive isoformat 반환. 날짜/시각 값만 검증.
    assert result["metadata"]["fetched_at"].startswith("2026-04-30T10:00:00")


@pytest.mark.asyncio
async def test_get_source_url_is_none(patched_session_factory):
    """캐시 조회이므로 source_url: null."""
    await _seed_cache("search_law_info", LAW_INFO_XML)

    result = await get_cached_data("search_law_info")

    assert result["source_url"] is None


# ─────────────────────────────────────────────
# get_cached_data — 도메인별 records key 검증
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_bill_info_structured_key(patched_session_factory):
    """의안 캐시 → structured.bills 키. search_bill_info는 param-keyed(AGE)."""
    await _seed_cache("search_bill_info", BILL_INFO_XML, site_url="cache://search_bill_info/22")

    result = await get_cached_data("search_bill_info", params={"age": 22})

    assert "bills" in result["structured"]


@pytest.mark.asyncio
async def test_get_public_job_structured_key(patched_session_factory):
    """공공 채용 캐시 → structured.postings 키."""
    await _seed_cache("search_public_job", PUBLIC_JOB_JSON)

    result = await get_cached_data("search_public_job")

    assert "postings" in result["structured"]


@pytest.mark.asyncio
async def test_get_public_job_cache_filters_by_subscription_params(patched_session_factory):
    """공공 채용 캐시도 fetch tool과 동일하게 구독 keyword/진행중 조건으로 추린다."""
    await _seed_cache("search_public_job", PUBLIC_JOB_MIXED_JSON)

    result = await get_cached_data(
        "search_public_job",
        params={
            "recrut_pbanc_ttl": "백엔드",
            "ongoing_yn": "Y",
            "page_no": 1,
            "num_of_rows": 20,
        },
    )

    structured = result["structured"]
    postings = structured["postings"]

    assert structured["query"]["recrut_pbanc_ttl"] == "백엔드"
    assert structured["query"]["ongoing_yn"] == "Y"
    assert structured["summary"]["count"] == 1
    assert structured["summary"]["ongoing_count"] == 1
    assert len(postings) == 1
    assert all("백엔드" in posting["title"] for posting in postings)
    assert all(posting["is_ongoing"] is True for posting in postings)
    assert result["metadata"]["raw_count"] == 3
    assert result["metadata"]["returned_count"] == 1


@pytest.mark.asyncio
async def test_get_worknet_job_cache_filters_by_subscription_params(patched_session_factory):
    """워크넷 채용 캐시도 fetch tool과 동일하게 keyword 조건으로 추린다."""
    await _seed_cache("search_worknet_job", WORKNET_JOB_MIXED_XML)

    result = await get_cached_data(
        "search_worknet_job",
        params={"keyword": "백엔드", "start_page": 1, "display": 20},
    )

    structured = result["structured"]
    postings = structured["postings"]

    assert structured["query"]["keyword"] == "백엔드"
    assert structured["summary"]["count"] == 2
    assert len(postings) == 2
    assert all(
        "백엔드" in (posting.get("title") or "")
        or "백엔드" in (posting.get("company") or "")
        for posting in postings
    )
    assert result["metadata"]["raw_count"] == 3
    assert result["metadata"]["returned_count"] == 2


@pytest.mark.asyncio
async def test_get_g2b_bid_structured_key(patched_session_factory):
    """경매 캐시 → structured.notices 키."""
    await _seed_cache("search_g2b_bid", G2B_BID_JSON)

    result = await get_cached_data("search_g2b_bid")

    assert "notices" in result["structured"]


# ─────────────────────────────────────────────
# get_cached_data — WorknetPermissionDenied
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_worknet_permission_denied(patched_session_factory):
    """권한 거부 XML이 캐시된 경우 → api_status: permission_denied."""
    await _seed_cache("search_worknet_job", WORKNET_PERMISSION_DENIED_XML)

    result = await get_cached_data("search_worknet_job")

    assert result["metadata"]["api_status"] == "permission_denied"
    assert result["metadata"]["cache_used"] is True
    assert result["structured"]["summary"]["count"] == 0
    assert result["structured"]["permission_denied"] is True


# ─────────────────────────────────────────────
# check_api_cache — param-keyed (부동산·의안)
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_check_real_estate_without_params(patched_session_factory):
    """부동산 tool에 params 없이 호출 → cache_hit: false + message."""
    result = await check_api_cache("search_house_price")

    assert result["cache_hit"] is False
    assert "message" in result
    assert "LAWD_CD" in result["message"]


@pytest.mark.asyncio
async def test_check_real_estate_cache_hit(patched_session_factory):
    """부동산 캐시 hit: 동일 params → cache_hit: true."""
    await _seed_cache(
        "search_house_price", APT_TRADE_XML,
        site_url="cache://search_house_price/11680/202403",
    )

    result = await check_api_cache(
        "search_house_price", params={"lawd_cd": "11680", "deal_ymd": "202403"}
    )

    assert result["cache_hit"] is True
    assert result["last_fetched_at"].startswith("2026-04-30T10:00:00")


@pytest.mark.asyncio
async def test_check_real_estate_different_region_is_miss(patched_session_factory):
    """강남(11680) 캐시 → 마포(11500) 조회 → cache_hit: false."""
    await _seed_cache(
        "search_house_price", APT_TRADE_XML,
        site_url="cache://search_house_price/11680/202403",
    )

    result = await check_api_cache(
        "search_house_price", params={"lawd_cd": "11500", "deal_ymd": "202403"}
    )

    assert result["cache_hit"] is False


@pytest.mark.asyncio
async def test_check_bill_info_age_keyed(patched_session_factory):
    """search_bill_info는 AGE 단위 param-keyed. AGE=22 캐시 → AGE=21 조회 → miss."""
    await _seed_cache("search_bill_info", BILL_INFO_XML, site_url="cache://search_bill_info/22")

    hit = await check_api_cache("search_bill_info", params={"age": 22})
    miss = await check_api_cache("search_bill_info", params={"age": 21})

    assert hit["cache_hit"] is True
    assert miss["cache_hit"] is False


# ─────────────────────────────────────────────
# get_cached_data — param-keyed (부동산)
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_real_estate_without_params(patched_session_factory):
    """부동산 tool에 params 없이 호출 → cache_hit: false + message."""
    result = await get_cached_data("search_house_price")

    assert result["cache_hit"] is False
    assert "message" in result


@pytest.mark.asyncio
async def test_get_real_estate_cache_hit_format(patched_session_factory):
    """부동산 캐시 hit → trades 키 + cache_used: true."""
    await _seed_cache(
        "search_house_price", APT_TRADE_XML,
        site_url="cache://search_house_price/11680/202403",
    )

    result = await get_cached_data(
        "search_house_price", params={"lawd_cd": "11680", "deal_ymd": "202403"}
    )

    assert result["metadata"]["cache_used"] is True
    assert result["source_url"] is None
    assert "trades" in result["structured"]
    assert "summary" in result["structured"]


@pytest.mark.asyncio
async def test_get_real_estate_different_region_is_miss(patched_session_factory):
    """강남 캐시 → 마포 조회 → cache_hit: false (조합 불일치)."""
    await _seed_cache(
        "search_house_price", APT_TRADE_XML,
        site_url="cache://search_house_price/11680/202403",
    )

    result = await get_cached_data(
        "search_house_price", params={"lawd_cd": "11500", "deal_ymd": "202403"}
    )

    assert result["cache_hit"] is False
