"""국토교통부 부동산 실거래가 응답 정규화.

api_source_service.fetch() 가 돌려준 XML 본문을 자료유형별 도메인 모델 list 로 변환.
정규화는 도메인 책임이라 sources 계층 예외(SourceError) 와 분리된
RealEstateNormalizationError 사용.

지원 자료유형 (5종):
- 아파트 매매 (AptTrade) — dealAmount
- 아파트 전월세 (AptRent) — deposit + monthlyRent
- 오피스텔 매매 (OffiTrade) — dealAmount, 단지명 offiNm
- 오피스텔 전월세 (OffiRent) — deposit + monthlyRent, 단지명 offiNm
- 연립다세대 전월세 (RHRent) — deposit + monthlyRent, 단지명 mhouseNm, houseType 보존
"""

from datetime import date
from xml.etree import ElementTree as ET

from pydantic import BaseModel, Field

from mcp_server.domains.real_estate.errors import RealEstateNormalizationError


# ─────────────────────────────────────────────
# 모델
# ─────────────────────────────────────────────

class AptTrade(BaseModel):
    """아파트 매매 실거래 1건."""

    apt_name: str = Field(description="단지명")
    deal_amount: int = Field(description="거래 금액 (만원 단위, 콤마 제거)")
    deal_date: date = Field(description="계약 일자")
    area: float = Field(description="전용면적 m²")
    floor: int = Field(description="층")
    lawd_cd: str = Field(description="법정동 코드 5자리")
    dong: str = Field(description="법정동명")
    jibun: str | None = Field(default=None, description="지번")
    build_year: int | None = Field(default=None, description="건축년도")


class AptRent(BaseModel):
    """아파트 전월세 실거래 1건. monthlyRent=0 이면 순수 전세."""

    apt_name: str = Field(description="단지명")
    deposit: int = Field(description="보증금 (만원, 콤마 제거)")
    monthly_rent: int = Field(description="월세 (만원). 0 이면 전세.")
    deal_date: date = Field(description="계약 일자")
    area: float = Field(description="전용면적 m²")
    floor: int = Field(description="층")
    lawd_cd: str = Field(description="법정동 코드 5자리")
    dong: str = Field(description="법정동명")
    jibun: str | None = Field(default=None, description="지번")
    build_year: int | None = Field(default=None, description="건축년도")


class OffiTrade(BaseModel):
    """오피스텔 매매 실거래 1건."""

    offi_name: str = Field(description="단지명")
    deal_amount: int = Field(description="거래 금액 (만원, 콤마 제거)")
    deal_date: date = Field(description="계약 일자")
    area: float = Field(description="전용면적 m²")
    floor: int = Field(description="층")
    lawd_cd: str = Field(description="법정동 코드 5자리")
    dong: str = Field(description="법정동명")
    jibun: str | None = Field(default=None, description="지번")
    build_year: int | None = Field(default=None, description="건축년도")


class OffiRent(BaseModel):
    """오피스텔 전월세 실거래 1건. monthlyRent=0 이면 순수 전세."""

    offi_name: str = Field(description="단지명")
    deposit: int = Field(description="보증금 (만원, 콤마 제거)")
    monthly_rent: int = Field(description="월세 (만원). 0 이면 전세.")
    deal_date: date = Field(description="계약 일자")
    area: float = Field(description="전용면적 m²")
    floor: int = Field(description="층")
    lawd_cd: str = Field(description="법정동 코드 5자리")
    dong: str = Field(description="법정동명")
    jibun: str | None = Field(default=None, description="지번")
    build_year: int | None = Field(default=None, description="건축년도")


class RHRent(BaseModel):
    """연립다세대 전월세 실거래 1건. monthlyRent=0 이면 순수 전세."""

    mhouse_name: str = Field(description="단지명 (mhouseNm)")
    house_type: str | None = Field(default=None, description="주택 유형 (예: '다세대', '연립')")
    deposit: int = Field(description="보증금 (만원, 콤마 제거)")
    monthly_rent: int = Field(description="월세 (만원). 0 이면 전세.")
    deal_date: date = Field(description="계약 일자")
    area: float = Field(description="전용면적 m²")
    floor: int = Field(description="층")
    lawd_cd: str = Field(description="법정동 코드 5자리")
    dong: str = Field(description="법정동명")
    jibun: str | None = Field(default=None, description="지번")
    build_year: int | None = Field(default=None, description="건축년도")


# ─────────────────────────────────────────────
# 정규화 함수
# ─────────────────────────────────────────────

_SUCCESS_CODE = "000"


def normalize_apt_trade(xml_text: str, *, lawd_cd: str) -> list[AptTrade]:
    """MOLIT 아파트 매매 실거래가 XML → list[AptTrade].

    Raises:
        RealEstateNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    items = _parse_items(xml_text)
    return [_build_apt_trade(item, lawd_cd=lawd_cd) for item in items]


def normalize_apt_rent(xml_text: str, *, lawd_cd: str) -> list[AptRent]:
    """MOLIT 아파트 전월세 실거래가 XML → list[AptRent].

    Raises:
        RealEstateNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    items = _parse_items(xml_text)
    return [_build_apt_rent(item, lawd_cd=lawd_cd) for item in items]


def normalize_offi_trade(xml_text: str, *, lawd_cd: str) -> list[OffiTrade]:
    """MOLIT 오피스텔 매매 실거래가 XML → list[OffiTrade].

    Raises:
        RealEstateNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    items = _parse_items(xml_text)
    return [_build_offi_trade(item, lawd_cd=lawd_cd) for item in items]


def normalize_offi_rent(xml_text: str, *, lawd_cd: str) -> list[OffiRent]:
    """MOLIT 오피스텔 전월세 실거래가 XML → list[OffiRent].

    Raises:
        RealEstateNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    items = _parse_items(xml_text)
    return [_build_offi_rent(item, lawd_cd=lawd_cd) for item in items]


def normalize_rh_rent(xml_text: str, *, lawd_cd: str) -> list[RHRent]:
    """MOLIT 연립다세대 전월세 실거래가 XML → list[RHRent].

    Raises:
        RealEstateNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
    items = _parse_items(xml_text)
    return [_build_rh_rent(item, lawd_cd=lawd_cd) for item in items]


# ─────────────────────────────────────────────
# 공통 파서: 응답 검증 + items 추출
# ─────────────────────────────────────────────

def _parse_items(xml_text: str) -> list[ET.Element]:
    """XML 파싱 + resultCode 검증 + items 반환. items 가 비어있으면 빈 리스트."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise RealEstateNormalizationError(f"MOLIT 응답 XML 파싱 실패: {exc}") from exc

    result_code = _find_text(root, "header/resultCode")
    if result_code != _SUCCESS_CODE:
        result_msg = _find_text(root, "header/resultMsg") or "(메시지 없음)"
        raise RealEstateNormalizationError(
            f"MOLIT API 비정상 응답: code={result_code}, msg={result_msg}"
        )

    return root.findall("body/items/item")


# ─────────────────────────────────────────────
# 자료유형별 builder
# ─────────────────────────────────────────────

def _build_apt_trade(item: ET.Element, *, lawd_cd: str) -> AptTrade:
    try:
        common = _build_common(item, lawd_cd=lawd_cd, name_tag="aptNm")
        deal_amount = _parse_amount(_required(item, "dealAmount"))
    except (KeyError, ValueError) as exc:
        raise RealEstateNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc
    return AptTrade(
        apt_name=common["name"],
        deal_amount=deal_amount,
        deal_date=common["deal_date"],
        area=common["area"],
        floor=common["floor"],
        lawd_cd=common["lawd_cd"],
        dong=common["dong"],
        jibun=common["jibun"],
        build_year=common["build_year"],
    )


def _build_apt_rent(item: ET.Element, *, lawd_cd: str) -> AptRent:
    try:
        common = _build_common(item, lawd_cd=lawd_cd, name_tag="aptNm")
        deposit, monthly_rent = _parse_rent(item)
    except (KeyError, ValueError) as exc:
        raise RealEstateNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc
    return AptRent(
        apt_name=common["name"],
        deposit=deposit,
        monthly_rent=monthly_rent,
        deal_date=common["deal_date"],
        area=common["area"],
        floor=common["floor"],
        lawd_cd=common["lawd_cd"],
        dong=common["dong"],
        jibun=common["jibun"],
        build_year=common["build_year"],
    )


def _build_offi_trade(item: ET.Element, *, lawd_cd: str) -> OffiTrade:
    try:
        common = _build_common(item, lawd_cd=lawd_cd, name_tag="offiNm")
        deal_amount = _parse_amount(_required(item, "dealAmount"))
    except (KeyError, ValueError) as exc:
        raise RealEstateNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc
    return OffiTrade(
        offi_name=common["name"],
        deal_amount=deal_amount,
        deal_date=common["deal_date"],
        area=common["area"],
        floor=common["floor"],
        lawd_cd=common["lawd_cd"],
        dong=common["dong"],
        jibun=common["jibun"],
        build_year=common["build_year"],
    )


def _build_offi_rent(item: ET.Element, *, lawd_cd: str) -> OffiRent:
    try:
        common = _build_common(item, lawd_cd=lawd_cd, name_tag="offiNm")
        deposit, monthly_rent = _parse_rent(item)
    except (KeyError, ValueError) as exc:
        raise RealEstateNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc
    return OffiRent(
        offi_name=common["name"],
        deposit=deposit,
        monthly_rent=monthly_rent,
        deal_date=common["deal_date"],
        area=common["area"],
        floor=common["floor"],
        lawd_cd=common["lawd_cd"],
        dong=common["dong"],
        jibun=common["jibun"],
        build_year=common["build_year"],
    )


def _build_rh_rent(item: ET.Element, *, lawd_cd: str) -> RHRent:
    try:
        common = _build_common(item, lawd_cd=lawd_cd, name_tag="mhouseNm")
        deposit, monthly_rent = _parse_rent(item)
    except (KeyError, ValueError) as exc:
        raise RealEstateNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc
    return RHRent(
        mhouse_name=common["name"],
        house_type=_find_text(item, "houseType"),
        deposit=deposit,
        monthly_rent=monthly_rent,
        deal_date=common["deal_date"],
        area=common["area"],
        floor=common["floor"],
        lawd_cd=common["lawd_cd"],
        dong=common["dong"],
        jibun=common["jibun"],
        build_year=common["build_year"],
    )


# ─────────────────────────────────────────────
# 공통 필드/유틸
# ─────────────────────────────────────────────

def _build_common(item: ET.Element, *, lawd_cd: str, name_tag: str) -> dict:
    """5종 자료유형 공통 필드 추출. 단지명 태그만 자료유형별로 다름."""
    deal_year = int(_required(item, "dealYear"))
    deal_month = int(_required(item, "dealMonth"))
    deal_day = int(_required(item, "dealDay"))
    return {
        "name": _required(item, name_tag),
        "deal_date": date(deal_year, deal_month, deal_day),
        "area": float(_required(item, "excluUseAr")),
        "floor": int(_required(item, "floor")),
        "lawd_cd": lawd_cd,
        "dong": _required(item, "umdNm"),
        "jibun": _find_text(item, "jibun"),
        "build_year": _optional_int(item, "buildYear"),
    }


def _parse_rent(item: ET.Element) -> tuple[int, int]:
    """전월세 가격 파싱. monthlyRent 는 보통 정수지만 콤마 가능성 대비."""
    deposit = _parse_amount(_required(item, "deposit"))
    monthly_rent = _parse_amount(_required(item, "monthlyRent"))
    return deposit, monthly_rent


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


def _optional_int(parent: ET.Element, tag: str) -> int | None:
    text = _find_text(parent, tag)
    if text is None:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def _parse_amount(raw: str) -> int:
    """'1,500' / '  1,500 ' → 1500. MOLIT 가격 필드는 콤마 포함 만원 단위 문자열."""
    return int(raw.replace(",", "").strip())


__all__ = [
    "AptRent",
    "AptTrade",
    "OffiRent",
    "OffiTrade",
    "RHRent",
    "RealEstateNormalizationError",
    "normalize_apt_rent",
    "normalize_apt_trade",
    "normalize_offi_rent",
    "normalize_offi_trade",
    "normalize_rh_rent",
]
