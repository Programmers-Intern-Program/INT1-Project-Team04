"""tools.jobs.* 단위 테스트.

실제 HTTP 호출 없이 api_source_service.fetch 를 stub 으로 교체해 도구 책임만 격리.
워크넷은 권한 거부 응답 → 정상 4필드 + permission_denied 플래그 변환 검증 포함.
"""

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from mcp_server.config import get_settings
from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session
from mcp_server.domains.jobs.errors import (
    JobsConfigError,
    JobsNormalizationError,
)
from mcp_server.sources import api_source_service
from mcp_server.sources.errors import SourceFetchError, SourceNotFoundError
from mcp_server.sources.result import RawResult
from mcp_server.tools import jobs
from mcp_server.tools.jobs import (
    SearchPublicJobInput,
    SearchWorknetJobInput,
    search_public_job,
    search_worknet_job,
)

FIXTURES = Path(__file__).resolve().parents[1] / "data" / "jobs"
PUBLIC_JOB_JSON = (FIXTURES / "search_public_job.json").read_text(encoding="utf-8")
WORKNET_JOB_XML = (FIXTURES / "search_worknet_job.xml").read_text(encoding="utf-8")


@pytest.fixture(autouse=True)
def reset_module_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(jobs, "_cached_source_ids", {})


@pytest.fixture(autouse=True)
def set_api_keys(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MOEF_PUBLIC_JOB_API_KEY", "test-public-key")
    monkeypatch.setenv("KEIS_WORKNET_JOB_API_KEY", "test-worknet-key")
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
# search_public_job
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_search_public_job_returns_common_schema(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """ALIO 픽스처 → 공통 4필드 스키마."""
    await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO 채용공시 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            PUBLIC_JOB_JSON,
            captured,
            tool_name="search_public_job",
            url_template="https://example.gov/moef/public-job",
        ),
    )

    result = await search_public_job(SearchPublicJobInput(num_of_rows=5))

    assert isinstance(result["text"], str) and result["text"]
    assert result["structured"]["summary"]["count"] == 5
    assert len(result["structured"]["postings"]) == 5
    assert result["structured"]["postings_truncated"] is False
    assert "serviceKey=***" in result["source_url"]
    assert result["metadata"]["raw_count"] == 5
    assert result["metadata"]["tool_name"] == "search_public_job"


@pytest.mark.asyncio
async def test_search_public_job_passes_optional_params(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """ongoing_yn / recrut_pbanc_ttl 명시 시 fetch params 에 포함."""
    await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            PUBLIC_JOB_JSON,
            captured,
            tool_name="search_public_job",
            url_template="https://example.gov/moef/public-job",
        ),
    )

    await search_public_job(
        SearchPublicJobInput(ongoing_yn="Y", recrut_pbanc_ttl="데이터")
    )

    assert captured["params"]["ongoingYn"] == "Y"
    assert captured["params"]["recrutPbancTtl"] == "데이터"
    assert captured["params"]["resultType"] == "json"


@pytest.mark.asyncio
async def test_search_public_job_optional_params_omitted_by_default(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """ongoing_yn / recrut_pbanc_ttl 미지정 시 params 에 포함되지 않음."""
    await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            PUBLIC_JOB_JSON,
            captured,
            tool_name="search_public_job",
            url_template="https://example.gov/moef/public-job",
        ),
    )

    await search_public_job(SearchPublicJobInput())

    assert "ongoingYn" not in captured["params"]
    assert "recrutPbancTtl" not in captured["params"]


@pytest.mark.asyncio
async def test_search_public_job_propagates_normalization_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """resultCode != 200 → JobsNormalizationError."""
    await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO (테스트)",
    )
    error_json = json.dumps(
        {"result": [], "resultCode": 401, "resultMsg": "auth fail", "totalCount": 0}
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            error_json,
            captured,
            tool_name="search_public_job",
            url_template="https://example.gov/moef/public-job",
        ),
    )

    with pytest.raises(JobsNormalizationError):
        await search_public_job(SearchPublicJobInput())


@pytest.mark.asyncio
async def test_search_public_job_raises_when_api_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """MOEF_PUBLIC_JOB_API_KEY 빈 값 → JobsConfigError, fetch 호출 X."""
    await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO (테스트)",
    )
    monkeypatch.setenv("MOEF_PUBLIC_JOB_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(JobsConfigError):
        await search_public_job(SearchPublicJobInput())


@pytest.mark.asyncio
async def test_search_public_job_propagates_source_not_found(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            PUBLIC_JOB_JSON,
            captured,
            tool_name="search_public_job",
            url_template="https://example.gov/moef/public-job",
        ),
    )

    with pytest.raises(SourceNotFoundError):
        await search_public_job(SearchPublicJobInput())


@pytest.mark.asyncio
async def test_search_public_job_propagates_fetch_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO (테스트)",
    )

    async def boom(*_args, **_kwargs):
        raise SourceFetchError("upstream 503")

    monkeypatch.setattr(api_source_service, "fetch", boom)

    with pytest.raises(SourceFetchError):
        await search_public_job(SearchPublicJobInput())


def test_search_public_job_input_does_not_expose_secret_key():
    fields = SearchPublicJobInput.model_fields
    assert "serviceKey" not in fields
    assert "service_key" not in fields


# ─────────────────────────────────────────────
# search_worknet_job — 권한 거부 분기
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_search_worknet_job_permission_denied_returns_normal_schema(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """권한 거부 응답 → 예외 X, 정상 4필드 + permission_denied=True."""
    await _seed_source(
        tool_name="search_worknet_job",
        url_template="https://example.gov/keis/worknet",
        name="워크넷 (테스트)",
    )
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            WORKNET_JOB_XML,
            captured,
            tool_name="search_worknet_job",
            url_template="https://example.gov/keis/worknet",
        ),
    )

    result = await search_worknet_job(SearchWorknetJobInput())

    assert result["structured"]["permission_denied"] is True
    assert result["metadata"]["api_status"] == "permission_denied"
    assert result["structured"]["summary"]["count"] == 0
    assert result["structured"]["postings"] == []
    assert "권한 미발급" in result["text"]
    assert "authKey=***" in result["source_url"]


@pytest.mark.asyncio
async def test_search_worknet_job_normal_response_returns_postings(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """정상 응답(<wantedRoot><pubJobs><wanted>...) → postings 리스트."""
    await _seed_source(
        tool_name="search_worknet_job",
        url_template="https://example.gov/keis/worknet",
        name="워크넷 (테스트)",
    )
    normal_xml = """<?xml version="1.0"?>
<wantedRoot>
  <pubJobs>
    <total>1</total>
    <wanted>
      <wantedTitle>백엔드 개발자</wantedTitle>
      <busplaName>테스트회사</busplaName>
      <regionNm>서울 강남구</regionNm>
      <empTpNm>정규직</empTpNm>
      <regDt>20260425</regDt>
      <closeDt>20260525</closeDt>
      <wantedInfoUrl>https://www.work.go.kr/x</wantedInfoUrl>
    </wanted>
  </pubJobs>
</wantedRoot>"""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            normal_xml,
            captured,
            tool_name="search_worknet_job",
            url_template="https://example.gov/keis/worknet",
        ),
    )

    result = await search_worknet_job(SearchWorknetJobInput(keyword="백엔드"))

    assert result["structured"]["permission_denied"] is False
    assert result["metadata"]["api_status"] == "ok"
    assert result["structured"]["summary"]["count"] == 1
    assert result["structured"]["postings"][0]["title"] == "백엔드 개발자"
    assert captured["params"]["keyword"] == "백엔드"


@pytest.mark.asyncio
async def test_search_worknet_job_propagates_non_permission_normalization_error(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """권한 거부 외의 normalize 에러는 전파 (도구가 catch 하지 않음)."""
    await _seed_source(
        tool_name="search_worknet_job",
        url_template="https://example.gov/keis/worknet",
        name="워크넷 (테스트)",
    )
    error_xml = """<?xml version="1.0"?><GO24><error>일시적 점검</error></GO24>"""
    captured: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            error_xml,
            captured,
            tool_name="search_worknet_job",
            url_template="https://example.gov/keis/worknet",
        ),
    )

    with pytest.raises(JobsNormalizationError):
        await search_worknet_job(SearchWorknetJobInput())


@pytest.mark.asyncio
async def test_search_worknet_job_raises_when_key_missing(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """KEIS_WORKNET_JOB_API_KEY 빈 값 → JobsConfigError, fetch 호출 X."""
    await _seed_source(
        tool_name="search_worknet_job",
        url_template="https://example.gov/keis/worknet",
        name="워크넷 (테스트)",
    )
    monkeypatch.setenv("KEIS_WORKNET_JOB_API_KEY", "")
    get_settings.cache_clear()

    async def fail_fetch(*_args, **_kwargs):
        raise AssertionError("fetch 가 호출되면 안 됨")

    monkeypatch.setattr(api_source_service, "fetch", fail_fetch)

    with pytest.raises(JobsConfigError):
        await search_worknet_job(SearchWorknetJobInput())


def test_search_worknet_job_input_does_not_expose_secret_key():
    fields = SearchWorknetJobInput.model_fields
    assert "authKey" not in fields
    assert "auth_key" not in fields


# ─────────────────────────────────────────────
# 캐시 격리
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_resolve_source_id_caches_per_tool_name(
    patched_session_factory, monkeypatch: pytest.MonkeyPatch
):
    """두 도구가 같은 _cached_source_ids dict 공유하지만 키 충돌 없음."""
    public_row = await _seed_source(
        tool_name="search_public_job",
        url_template="https://example.gov/moef/public-job",
        name="ALIO",
    )
    worknet_row = await _seed_source(
        tool_name="search_worknet_job",
        url_template="https://example.gov/keis/worknet",
        name="워크넷",
    )

    captured1: dict[str, Any] = {}
    captured2: dict[str, Any] = {}
    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            PUBLIC_JOB_JSON,
            captured1,
            tool_name="search_public_job",
            url_template="https://example.gov/moef/public-job",
        ),
    )
    await search_public_job(SearchPublicJobInput())

    monkeypatch.setattr(
        api_source_service,
        "fetch",
        _make_fake_fetch(
            WORKNET_JOB_XML,
            captured2,
            tool_name="search_worknet_job",
            url_template="https://example.gov/keis/worknet",
        ),
    )
    await search_worknet_job(SearchWorknetJobInput())

    assert jobs._cached_source_ids["search_public_job"] == public_row.id
    assert jobs._cached_source_ids["search_worknet_job"] == worknet_row.id
