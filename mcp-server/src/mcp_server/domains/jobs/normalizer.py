"""기획재정부 ALIO 공공기관 채용 / 한국고용정보원 워크넷 채용정보 응답 정규화.

api_source_service.fetch() 가 돌려준 본문 (ALIO=JSON, 워크넷=XML) 을 도메인 모델
list 로 변환. 정규화는 도메인 책임이라 sources 계층 예외와 분리된
JobsNormalizationError 를 사용한다.

지원 응답 (2종):
- ALIO 채용공시 목록 (JSON, resultCode 200) → list[PublicJobPosting]
- 워크넷 채용정보 (XML)
  - 권한 거부 응답 (<error>개인회원은 ...</error>) → WorknetPermissionDeniedError
  - 정상 응답 (<wantedRoot><pubJobs><wanted>...</wanted></pubJobs></wantedRoot>)
    → list[WorknetJobPosting]

워크넷 정상 응답 정규화는 추정 명세 기반으로 작성되어 있으며, 사업자/기관 권한
확보 후 픽스처(tests/data/jobs/search_worknet_job.xml) 를 갱신한 시점에서
실응답 구조에 맞춰 보강해야 한다.
"""

import json
from datetime import date
from xml.etree import ElementTree as ET

from pydantic import BaseModel, Field

from mcp_server.domains.jobs.errors import (
    JobsNormalizationError,
    WorknetPermissionDeniedError,
)

_PUBLIC_JOB_SUCCESS_CODE = 200


# ─────────────────────────────────────────────
# 모델
# ─────────────────────────────────────────────


class PublicJobPosting(BaseModel):
    """ALIO 공공기관 채용공시 1건."""

    pblnt_sn: int = Field(description="공시 일련번호 (recrutPblntSn)")
    title: str = Field(description="공고 제목")
    institute: str = Field(description="기관명")
    institute_code: str | None = Field(default=None, description="공시기관코드")
    hire_type: str | None = Field(default=None, description="고용형태명 (정규직/비정규직 등)")
    recruit_type: str | None = Field(default=None, description="채용구분 (신입/경력/신입+경력 등)")
    ncs_categories: list[str] = Field(default_factory=list, description="NCS 직무 분류명 리스트")
    work_regions: list[str] = Field(default_factory=list, description="근무지역명 리스트")
    headcount: int | None = Field(default=None, description="채용 인원")
    pbanc_begin_date: date | None = Field(default=None, description="공고 시작일")
    pbanc_end_date: date | None = Field(default=None, description="공고 마감일")
    is_ongoing: bool | None = Field(default=None, description="진행 여부 (ongoingYn=Y)")
    src_url: str | None = Field(default=None, description="원문 공고 URL")


class WorknetJobPosting(BaseModel):
    """워크넷 채용공고 1건 (정상 응답 구조 기준)."""

    title: str = Field(description="채용 제목 (wantedTitle)")
    company: str = Field(description="회사명/사업장명 (busplaName)")
    region: str | None = Field(default=None, description="근무지역명 (regionNm)")
    emp_type: str | None = Field(default=None, description="고용형태명 (empTpNm)")
    min_education: str | None = Field(default=None, description="최소학력 (minEdubgNm)")
    salary_type: str | None = Field(default=None, description="임금형태명 (salTpNm)")
    reg_date: date | None = Field(default=None, description="등록일자")
    close_date: date | None = Field(default=None, description="마감일자")
    info_url: str | None = Field(default=None, description="상세 URL (wantedInfoUrl)")


# ─────────────────────────────────────────────
# 정규화 — ALIO 공공기관 채용 (JSON)
# ─────────────────────────────────────────────


def normalize_public_job(json_text: str) -> list[PublicJobPosting]:
    """ALIO 공공기관 채용공시 JSON 응답 → list[PublicJobPosting].

    Raises:
        JobsNormalizationError: 응답 코드 비정상, JSON 파싱 실패, 필수 필드 누락.
    """
    try:
        payload = json.loads(json_text)
    except json.JSONDecodeError as exc:
        raise JobsNormalizationError(f"ALIO 응답 JSON 파싱 실패: {exc}") from exc

    code = payload.get("resultCode")
    if code != _PUBLIC_JOB_SUCCESS_CODE:
        msg = payload.get("resultMsg") or "(메시지 없음)"
        raise JobsNormalizationError(f"ALIO API 비정상 응답: code={code}, msg={msg}")

    items = payload.get("result") or []
    if not isinstance(items, list):
        raise JobsNormalizationError(
            f"ALIO 응답 result 형식 비정상: {type(items).__name__}"
        )
    return [_build_public_posting(item) for item in items]


def _build_public_posting(item: dict) -> PublicJobPosting:
    try:
        return PublicJobPosting(
            pblnt_sn=int(_required(item, "recrutPblntSn")),
            title=_required(item, "recrutPbancTtl"),
            institute=_required(item, "instNm"),
            institute_code=_optional_str(item, "pblntInstCd"),
            hire_type=_optional_str(item, "hireTypeNmLst"),
            recruit_type=_optional_str(item, "recrutSeNm"),
            ncs_categories=_split_csv(item.get("ncsCdNmLst")),
            work_regions=_split_csv(item.get("workRgnNmLst")),
            headcount=_optional_int_value(item.get("recrutNope")),
            pbanc_begin_date=_optional_yyyymmdd(item.get("pbancBgngYmd")),
            pbanc_end_date=_optional_yyyymmdd(item.get("pbancEndYmd")),
            is_ongoing=_optional_yn(item.get("ongoingYn")),
            src_url=_optional_str(item, "srcUrl"),
        )
    except (KeyError, ValueError) as exc:
        raise JobsNormalizationError(
            f"ALIO result 항목 필수 필드 파싱 실패: {exc}"
        ) from exc


# ─────────────────────────────────────────────
# 정규화 — 워크넷 채용 (XML, 권한 거부 분기)
# ─────────────────────────────────────────────


def normalize_worknet_job(xml_text: str) -> list[WorknetJobPosting]:
    """워크넷 채용공고 XML 응답 → list[WorknetJobPosting].

    권한 거부 응답(<GO24><error>...개인회원...</error></GO24>) 은 일반 정규화 실패와
    구분된 WorknetPermissionDeniedError 로 raise. 도구 레이어에서 catch 해
    사용자에게 "권한 미발급" 안내 응답으로 변환할 수 있다.

    Raises:
        WorknetPermissionDeniedError: 개인회원 권한 거부 응답.
        JobsNormalizationError: 그 외 응답 형식 오류 (XML 파싱, 일반 에러 메시지).
    """
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise JobsNormalizationError(f"워크넷 응답 XML 파싱 실패: {exc}") from exc

    error_node = root.find("error")
    if error_node is not None:
        msg = (error_node.text or "").strip() or "(메시지 없음)"
        if "사용할 수 없는" in msg or "개인회원" in msg:
            raise WorknetPermissionDeniedError(msg)
        raise JobsNormalizationError(f"워크넷 API 에러: {msg}")

    # 정상 응답 구조: <wantedRoot><pubJobs><wanted>...</wanted></pubJobs></wantedRoot>
    # findall(".//wanted") 로 루트 위치 변동에 견고하게 추출.
    return [_build_worknet_posting(node) for node in root.findall(".//wanted")]


def _build_worknet_posting(node: ET.Element) -> WorknetJobPosting:
    try:
        return WorknetJobPosting(
            title=_required_node(node, "wantedTitle"),
            company=_required_node(node, "busplaName"),
            region=_node_text(node, "regionNm"),
            emp_type=_node_text(node, "empTpNm"),
            min_education=_node_text(node, "minEdubgNm"),
            salary_type=_node_text(node, "salTpNm"),
            reg_date=_optional_yyyymmdd(_node_text(node, "regDt")),
            close_date=_optional_yyyymmdd(_node_text(node, "closeDt")),
            info_url=_node_text(node, "wantedInfoUrl"),
        )
    except (KeyError, ValueError) as exc:
        raise JobsNormalizationError(
            f"워크넷 wanted 항목 필수 필드 파싱 실패: {exc}"
        ) from exc


# ─────────────────────────────────────────────
# 공통 유틸 — JSON dict 용
# ─────────────────────────────────────────────


def _required(item: dict, key: str) -> str:
    value = item.get(key)
    if value is None or value == "":
        raise KeyError(key)
    return str(value).strip()


def _optional_str(item: dict, key: str) -> str | None:
    value = item.get(key)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int_value(value) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(str(value).strip())
    except ValueError:
        return None


def _optional_yyyymmdd(value) -> date | None:
    """'YYYYMMDD' 문자열/정수 → date. 형식 어긋나면 None."""
    if value is None:
        return None
    text = str(value).strip()
    if len(text) != 8 or not text.isdigit():
        return None
    try:
        return date(int(text[:4]), int(text[4:6]), int(text[6:]))
    except ValueError:
        return None


def _optional_yn(value) -> bool | None:
    if value is None:
        return None
    text = str(value).strip().upper()
    if text == "Y":
        return True
    if text == "N":
        return False
    return None


def _split_csv(value) -> list[str]:
    """ALIO 의 'R600023,R600015' 형식 CSV → ['R600023', 'R600015']. 빈 값은 빈 리스트."""
    if value is None:
        return []
    text = str(value).strip()
    if not text:
        return []
    return [part.strip() for part in text.split(",") if part.strip()]


# ─────────────────────────────────────────────
# 공통 유틸 — XML node 용
# ─────────────────────────────────────────────


def _node_text(parent: ET.Element, tag: str) -> str | None:
    node = parent.find(tag)
    if node is None or node.text is None:
        return None
    return node.text.strip() or None


def _required_node(parent: ET.Element, tag: str) -> str:
    text = _node_text(parent, tag)
    if text is None:
        raise KeyError(tag)
    return text


__all__ = [
    "JobsNormalizationError",
    "PublicJobPosting",
    "WorknetJobPosting",
    "WorknetPermissionDeniedError",
    "normalize_public_job",
    "normalize_worknet_job",
]
