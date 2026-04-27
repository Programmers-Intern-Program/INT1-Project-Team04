"""real_estate.normalizer 단위 테스트.

XML 픽스처 (tests/fixtures/molit_apt_trade_*.xml) 기반.
실제 API 호출 없음 — 정규화 로직만 검증.
"""

from datetime import date
from pathlib import Path

import pytest

from mcp_server.domains.real_estate.normalizer import (
    AptTrade,
    RealEstateNormalizationError,
    normalize_apt_trade,
)

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


def _load(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def test_normalize_returns_apt_trades():
    """정상 응답 → AptTrade 리스트, 첫 row 의 핵심 필드 검증."""
    result = normalize_apt_trade(_load("molit_apt_trade_sample.xml"), lawd_cd="11680")

    assert len(result) == 2
    assert all(isinstance(row, AptTrade) for row in result)

    first = result[0]
    assert first.apt_name == "래미안"
    assert first.deal_amount == 120000
    assert first.deal_date == date(2024, 3, 15)
    assert first.area == 84.97
    assert first.floor == 10
    assert first.lawd_cd == "11680"
    assert first.dong == "역삼동"
    assert first.jibun == "758"
    assert first.build_year == 2010


def test_normalize_strips_price_commas():
    """'  120,000 ' 같은 가격 문자열은 콤마/공백 제거 후 int."""
    result = normalize_apt_trade(_load("molit_apt_trade_sample.xml"), lawd_cd="11680")

    assert result[0].deal_amount == 120000
    assert result[1].deal_amount == 95500


def test_normalize_handles_empty_items():
    """totalCount=0 + 빈 <items> → 빈 리스트 (예외 아님)."""
    result = normalize_apt_trade(_load("molit_apt_trade_empty.xml"), lawd_cd="11680")

    assert result == []


def test_normalize_raises_on_error_response():
    """resultCode != '000' → RealEstateNormalizationError, 메시지에 코드/사유 포함."""
    with pytest.raises(RealEstateNormalizationError) as excinfo:
        normalize_apt_trade(_load("molit_apt_trade_error.xml"), lawd_cd="11680")

    msg = str(excinfo.value)
    assert "30" in msg
    assert "SERVICE KEY" in msg


def test_normalize_raises_on_malformed_xml():
    """깨진 XML → ParseError 흡수해 RealEstateNormalizationError."""
    with pytest.raises(RealEstateNormalizationError):
        normalize_apt_trade("<response><header><resultCode>", lawd_cd="11680")
