"""normalize_public_job / normalize_worknet_job 정규화 단위 테스트.

실제 응답 픽스처 (tests/data/jobs/*.{json,xml}) + 인공 케이스로 경계 조건 검증.

워크넷은 현재 사업자/기관 권한 미발급 상태라 정상 응답 픽스처가 없다.
권한 거부 분기는 실제 픽스처 기반, 정상 응답 정규화는 인공 XML 기반으로 검증.
"""

import json
from datetime import date
from pathlib import Path

import pytest

from mcp_server.domains.jobs.errors import (
    JobsNormalizationError,
    WorknetPermissionDeniedError,
)
from mcp_server.domains.jobs.normalizer import (
    PublicJobPosting,
    WorknetJobPosting,
    normalize_public_job,
    normalize_worknet_job,
)

FIXTURES = Path(__file__).resolve().parents[2] / "data" / "jobs"
PUBLIC_JOB_JSON = (FIXTURES / "search_public_job.json").read_text(encoding="utf-8")
WORKNET_JOB_XML = (FIXTURES / "search_worknet_job.xml").read_text(encoding="utf-8")


def _wrap_public_job(items: list[dict]) -> str:
    return json.dumps(
        {
            "result": items,
            "resultCode": 200,
            "resultMsg": "성공했습니다.",
            "totalCount": len(items),
        }
    )


# ─────────────────────────────────────────────
# normalize_public_job (ALIO JSON)
# ─────────────────────────────────────────────


def test_normalize_public_job_real_fixture_returns_5_records():
    """ALIO 픽스처(rows=5) → 5개 PublicJobPosting."""
    records = normalize_public_job(PUBLIC_JOB_JSON)
    assert len(records) == 5
    assert all(isinstance(r, PublicJobPosting) for r in records)


def test_normalize_public_job_extracts_core_fields():
    """첫 번째 항목의 핵심 필드 검증."""
    records = normalize_public_job(PUBLIC_JOB_JSON)
    first = records[0]
    assert first.title  # recrutPbancTtl
    assert first.institute  # instNm
    assert isinstance(first.pblnt_sn, int)


def test_normalize_public_job_parses_yyyymmdd_dates():
    """pbancBgngYmd / pbancEndYmd 'YYYYMMDD' 가 date 로 파싱."""
    records = normalize_public_job(PUBLIC_JOB_JSON)
    for r in records:
        assert isinstance(r.pbanc_begin_date, date)
        assert isinstance(r.pbanc_end_date, date)


def test_normalize_public_job_splits_csv_categories():
    """ncsCdNmLst 의 CSV → list[str] 변환."""
    records = normalize_public_job(PUBLIC_JOB_JSON)
    has_multiple = [r for r in records if len(r.ncs_categories) > 1]
    assert has_multiple, "픽스처에 NCS 다중 카테고리 항목 없음"
    assert all(isinstance(c, str) for c in has_multiple[0].ncs_categories)


def test_normalize_public_job_yn_flag_to_bool():
    """ongoingYn='Y' → True, 'N' → False, 미지정/잘못된 값 → None."""
    records = normalize_public_job(PUBLIC_JOB_JSON)
    # 픽스처 5건 모두 Y
    assert all(r.is_ongoing is True for r in records)


def test_normalize_public_job_raises_on_error_response():
    """resultCode != 200 → JobsNormalizationError."""
    payload = json.dumps(
        {"result": [], "resultCode": 401, "resultMsg": "인증 실패", "totalCount": 0}
    )
    with pytest.raises(JobsNormalizationError) as exc:
        normalize_public_job(payload)
    assert "401" in str(exc.value)


def test_normalize_public_job_raises_on_invalid_json():
    """JSON 파싱 실패 → JobsNormalizationError."""
    with pytest.raises(JobsNormalizationError):
        normalize_public_job("not a json")


def test_normalize_public_job_empty_result_returns_empty_list():
    """result=[] → 빈 리스트."""
    assert normalize_public_job(_wrap_public_job([])) == []


def test_normalize_public_job_required_field_missing_raises():
    """recrutPblntSn 누락 → JobsNormalizationError."""
    item = {
        "recrutPbancTtl": "테스트 공고",
        "instNm": "테스트 기관",
    }
    with pytest.raises(JobsNormalizationError):
        normalize_public_job(_wrap_public_job([item]))


def test_normalize_public_job_invalid_yyyymmdd_becomes_none():
    """잘못된 8자리 일자 → None, 예외 없음."""
    item = {
        "recrutPblntSn": 1,
        "recrutPbancTtl": "테스트",
        "instNm": "테스트 기관",
        "pbancBgngYmd": "99999999",
        "pbancEndYmd": "",
    }
    records = normalize_public_job(_wrap_public_job([item]))
    assert records[0].pbanc_begin_date is None
    assert records[0].pbanc_end_date is None


# ─────────────────────────────────────────────
# normalize_worknet_job — 권한 거부 분기
# ─────────────────────────────────────────────


def test_normalize_worknet_job_permission_denied_real_fixture():
    """실제 픽스처(<error>개인회원...</error>) → WorknetPermissionDeniedError."""
    with pytest.raises(WorknetPermissionDeniedError) as exc:
        normalize_worknet_job(WORKNET_JOB_XML)
    assert "개인회원" in str(exc.value) or "사용할 수 없는" in str(exc.value)


def test_normalize_worknet_job_generic_error_raises_normal_error():
    """<error> 자식이지만 권한 거부 메시지가 아니면 일반 JobsNormalizationError."""
    xml = """<?xml version="1.0"?>
<GO24><error>일시적인 서비스 점검 중입니다.</error></GO24>"""
    with pytest.raises(JobsNormalizationError) as exc:
        normalize_worknet_job(xml)
    # 권한 거부 서브클래스가 아니어야 함
    assert not isinstance(exc.value, WorknetPermissionDeniedError)


def test_normalize_worknet_job_raises_on_invalid_xml():
    """XML 파싱 실패 → JobsNormalizationError."""
    with pytest.raises(JobsNormalizationError):
        normalize_worknet_job("<not><valid")


# ─────────────────────────────────────────────
# normalize_worknet_job — 정상 응답 (추정 명세, 인공 XML)
# ─────────────────────────────────────────────


def test_normalize_worknet_job_normal_response_extracts_records():
    """정상 응답 구조 (<wantedRoot><pubJobs><wanted>...</wanted></pubJobs></wantedRoot>) 정규화."""
    xml = """<?xml version="1.0"?>
<wantedRoot>
  <pubJobs>
    <total>2</total>
    <wanted>
      <wantedTitle>백엔드 개발자 (Java)</wantedTitle>
      <busplaName>테스트회사</busplaName>
      <regionNm>서울 강남구</regionNm>
      <empTpNm>정규직</empTpNm>
      <minEdubgNm>대졸</minEdubgNm>
      <salTpNm>연봉</salTpNm>
      <regDt>20260420</regDt>
      <closeDt>20260520</closeDt>
      <wantedInfoUrl>https://www.work.go.kr/...</wantedInfoUrl>
    </wanted>
    <wanted>
      <wantedTitle>프론트엔드 개발자</wantedTitle>
      <busplaName>다른회사</busplaName>
      <regionNm>경기 성남시</regionNm>
      <empTpNm>계약직</empTpNm>
      <minEdubgNm>학력무관</minEdubgNm>
      <salTpNm>월급</salTpNm>
      <regDt>20260425</regDt>
      <closeDt>20260525</closeDt>
      <wantedInfoUrl>https://www.work.go.kr/...</wantedInfoUrl>
    </wanted>
  </pubJobs>
</wantedRoot>"""
    records = normalize_worknet_job(xml)
    assert len(records) == 2
    assert all(isinstance(r, WorknetJobPosting) for r in records)
    assert records[0].title == "백엔드 개발자 (Java)"
    assert records[0].region == "서울 강남구"
    assert records[0].reg_date == date(2026, 4, 20)


def test_normalize_worknet_job_empty_pub_jobs_returns_empty_list():
    """<wanted> 노드 없음 → 빈 리스트 (에러 아님)."""
    xml = """<?xml version="1.0"?>
<wantedRoot><pubJobs><total>0</total></pubJobs></wantedRoot>"""
    assert normalize_worknet_job(xml) == []


def test_normalize_worknet_job_required_field_missing_raises():
    """wantedTitle 누락 → JobsNormalizationError."""
    xml = """<?xml version="1.0"?>
<wantedRoot><pubJobs><wanted>
  <busplaName>회사</busplaName>
</wanted></pubJobs></wantedRoot>"""
    with pytest.raises(JobsNormalizationError):
        normalize_worknet_job(xml)


def test_normalize_worknet_job_invalid_dates_become_none():
    """잘못된 일자 형식 → None, 예외 없음."""
    xml = """<?xml version="1.0"?>
<wantedRoot><pubJobs><wanted>
  <wantedTitle>테스트</wantedTitle>
  <busplaName>회사</busplaName>
  <regDt>2026-04-20</regDt>
  <closeDt></closeDt>
</wanted></pubJobs></wantedRoot>"""
    records = normalize_worknet_job(xml)
    assert records[0].reg_date is None
    assert records[0].close_date is None
