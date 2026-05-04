"""법제처 국가법령정보 / 국회 의안정보 응답 정규화.

api_source_service.fetch() 가 돌려준 XML 본문을 도메인 모델 list 로 변환.
정규화는 도메인 책임이라 sources 계층 예외와 분리된 LawNormalizationError 사용.

지원 응답 (2종):
- 법제처 현행법령 목록 (LawSearch 루트, 한글 태그, resultCode "00")
  → list[LawItem]
- 국회 의안 검색 (서비스ID 임의 루트, head/RESULT/CODE "INFO-000", row 반복)
  → list[BillItem]
"""

from datetime import date
from xml.etree import ElementTree as ET

from pydantic import BaseModel, Field

from mcp_server.domains.law.errors import LawNormalizationError

_LAW_SUCCESS_CODE = "00"
_BILL_SUCCESS_CODE = "INFO-000"


# ─────────────────────────────────────────────
# 모델
# ─────────────────────────────────────────────


class LawItem(BaseModel):
    """현행법령 1건 (법제처 국가법령정보 공유서비스)."""

    law_no: str = Field(description="법령일련번호 (MST 식별자)")
    law_id: str = Field(description="법령ID")
    law_name: str = Field(description="법령명한글")
    law_alias: str | None = Field(default=None, description="법령약칭명")
    law_kind: str | None = Field(default=None, description="법령구분명 (법률/대통령령/부령 등)")
    amend_kind: str | None = Field(default=None, description="제개정구분명 (일부개정/전부개정/제정 등)")
    ministry_name: str | None = Field(default=None, description="소관부처명")
    ministry_code: str | None = Field(default=None, description="소관부처코드")
    promulgation_date: date | None = Field(default=None, description="공포일자")
    promulgation_no: str | None = Field(default=None, description="공포번호")
    enforcement_date: date | None = Field(default=None, description="시행일자")
    current_status: str | None = Field(default=None, description="현행연혁코드 (현행/연혁 등)")
    detail_link: str | None = Field(default=None, description="법령 상세 링크 (상대 경로)")


class BillItem(BaseModel):
    """국회 의안 1건 (열린국회정보 의안검색)."""

    bill_id: str = Field(description="의안ID (BILL_ID)")
    bill_no: str = Field(description="의안번호 (BILL_NO)")
    bill_name: str = Field(description="의안명")
    age: int | None = Field(default=None, description="국회 대수 (1~22)")
    propose_date: date | None = Field(default=None, description="발의일자")
    proposer: str | None = Field(default=None, description="대표 발의자 (예: '김미애의원 등 14인')")
    committee: str | None = Field(default=None, description="소관위원회")
    proc_result: str | None = Field(default=None, description="처리결과")
    detail_link: str | None = Field(default=None, description="의안 상세 링크")


# ─────────────────────────────────────────────
# 정규화 함수
# ─────────────────────────────────────────────


def normalize_law_info(xml_text: str) -> list[LawItem]:
    """법제처 현행법령 목록 XML → list[LawItem].

    Raises:
        LawNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    root = _parse_root(xml_text)
    code = _find_text(root, "resultCode")
    if code != _LAW_SUCCESS_CODE:
        msg = _find_text(root, "resultMsg") or "(메시지 없음)"
        raise LawNormalizationError(
            f"법제처 API 비정상 응답: code={code}, msg={msg}"
        )
    return [_build_law_item(node) for node in root.findall("law")]


def normalize_bill_info(xml_text: str) -> list[BillItem]:
    """국회 의안 검색 XML → list[BillItem].

    응답 루트는 서비스ID(임의 이름)라 root.findall("row") 패턴으로 추출.

    Raises:
        LawNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    root = _parse_root(xml_text)
    code = _find_text(root, "head/RESULT/CODE")
    if code != _BILL_SUCCESS_CODE:
        msg = _find_text(root, "head/RESULT/MESSAGE") or "(메시지 없음)"
        raise LawNormalizationError(
            f"열린국회정보 API 비정상 응답: code={code}, msg={msg}"
        )
    return [_build_bill_item(node) for node in root.findall("row")]


# ─────────────────────────────────────────────
# Builder
# ─────────────────────────────────────────────


def _build_law_item(node: ET.Element) -> LawItem:
    try:
        return LawItem(
            law_no=_required(node, "법령일련번호"),
            law_id=_required(node, "법령ID"),
            law_name=_required(node, "법령명한글"),
            law_alias=_optional_text(node, "법령약칭명"),
            law_kind=_optional_text(node, "법령구분명"),
            amend_kind=_optional_text(node, "제개정구분명"),
            ministry_name=_optional_text(node, "소관부처명"),
            ministry_code=_optional_text(node, "소관부처코드"),
            promulgation_date=_optional_yyyymmdd(node, "공포일자"),
            promulgation_no=_optional_text(node, "공포번호"),
            enforcement_date=_optional_yyyymmdd(node, "시행일자"),
            current_status=_optional_text(node, "현행연혁코드"),
            detail_link=_optional_text(node, "법령상세링크"),
        )
    except (KeyError, ValueError) as exc:
        raise LawNormalizationError(f"law 항목 필수 필드 파싱 실패: {exc}") from exc


def _build_bill_item(node: ET.Element) -> BillItem:
    try:
        return BillItem(
            bill_id=_required(node, "BILL_ID"),
            bill_no=_required(node, "BILL_NO"),
            bill_name=_required(node, "BILL_NAME"),
            age=_optional_int(node, "AGE"),
            propose_date=_optional_iso_date(node, "PROPOSE_DT"),
            proposer=_optional_text(node, "PROPOSER"),
            committee=_optional_text(node, "COMMITTEE"),
            proc_result=_optional_text(node, "PROC_RESULT"),
            detail_link=_optional_text(node, "DETAIL_LINK"),
        )
    except (KeyError, ValueError) as exc:
        raise LawNormalizationError(f"row 항목 필수 필드 파싱 실패: {exc}") from exc


# ─────────────────────────────────────────────
# 공통 유틸
# ─────────────────────────────────────────────


def _parse_root(xml_text: str) -> ET.Element:
    try:
        return ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise LawNormalizationError(f"법률 응답 XML 파싱 실패: {exc}") from exc


def _find_text(parent: ET.Element, path: str) -> str | None:
    node = parent.find(path)
    if node is None or node.text is None:
        return None
    return node.text.strip() or None


def _required(parent: ET.Element, tag: str) -> str:
    text = _find_text(parent, tag)
    if text is None:
        raise KeyError(tag)
    return text


def _optional_text(parent: ET.Element, tag: str) -> str | None:
    return _find_text(parent, tag)


def _optional_int(parent: ET.Element, tag: str) -> int | None:
    text = _find_text(parent, tag)
    if text is None:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def _optional_yyyymmdd(parent: ET.Element, tag: str) -> date | None:
    """법제처 일자 포맷 'YYYYMMDD' → date. 8자리 숫자 아니면 None."""
    text = _find_text(parent, tag)
    if text is None or len(text) != 8 or not text.isdigit():
        return None
    try:
        return date(int(text[:4]), int(text[4:6]), int(text[6:]))
    except ValueError:
        return None


def _optional_iso_date(parent: ET.Element, tag: str) -> date | None:
    """의안 일자 포맷 'YYYY-MM-DD' → date. 형식 어긋나면 None."""
    text = _find_text(parent, tag)
    if text is None:
        return None
    try:
        return date.fromisoformat(text)
    except ValueError:
        return None


__all__ = [
    "BillItem",
    "LawItem",
    "LawNormalizationError",
    "normalize_bill_info",
    "normalize_law_info",
]
