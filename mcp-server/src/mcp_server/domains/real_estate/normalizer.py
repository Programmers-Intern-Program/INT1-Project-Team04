"""국토교통부 아파트 매매 실거래가 응답 정규화.

api_source_service.fetch() 가 돌려준 XML 본문을 도메인 모델 list[AptTrade] 로 변환.
정규화는 도메인 책임이라 sources 계층 예외(SourceError) 와 분리된 RealEstateNormalizationError 사용.
"""

from datetime import date
from xml.etree import ElementTree as ET

from pydantic import BaseModel, Field

from mcp_server.domains.real_estate.errors import RealEstateNormalizationError


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


_SUCCESS_CODE = "000"


def normalize_apt_trade(xml_text: str, *, lawd_cd: str) -> list[AptTrade]:
    """MOLIT 아파트 매매 실거래가 XML → list[AptTrade].

    Args:
        xml_text: api_source_service.fetch() 의 RawResult.content (XML 텍스트).
        lawd_cd: 호출 시 사용한 법정동 코드 5자리. 응답 자체엔 직접 안 들어있어 호출자가 보존.

    Raises:
        RealEstateNormalizationError: 응답 코드 비정상, XML 파싱 실패, 필수 필드 누락.
    """
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

    items = root.findall("body/items/item")
    if not items:
        return []

    return [_build_apt_trade(item, lawd_cd=lawd_cd) for item in items]


def _build_apt_trade(item: ET.Element, *, lawd_cd: str) -> AptTrade:
    try:
        deal_year = int(_required(item, "dealYear"))
        deal_month = int(_required(item, "dealMonth"))
        deal_day = int(_required(item, "dealDay"))
        deal_amount = _parse_amount(_required(item, "dealAmount"))
        area = float(_required(item, "excluUseAr"))
        floor = int(_required(item, "floor"))
        apt_name = _required(item, "aptNm")
        dong = _required(item, "umdNm")
    except (KeyError, ValueError) as exc:
        raise RealEstateNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc

    return AptTrade(
        apt_name=apt_name,
        deal_amount=deal_amount,
        deal_date=date(deal_year, deal_month, deal_day),
        area=area,
        floor=floor,
        lawd_cd=lawd_cd,
        dong=dong,
        jibun=_find_text(item, "jibun"),
        build_year=_optional_int(item, "buildYear"),
    )


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


__all__ = ["AptTrade", "RealEstateNormalizationError", "normalize_apt_trade"]
