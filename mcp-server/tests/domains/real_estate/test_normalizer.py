"""real_estate.normalizer 단위 테스트.

XML 픽스처 (tests/fixtures/molit_*_{sample,empty,error}.xml) 기반.
실제 API 호출 없음 — 정규화 로직만 검증.

자료유형 5종:
- AptTrade  (아파트 매매)     — dealAmount
- AptRent   (아파트 전월세)   — deposit + monthlyRent
- OffiTrade (오피스텔 매매)   — dealAmount, offiNm
- OffiRent  (오피스텔 전월세) — deposit + monthlyRent, offiNm
- RHRent    (연립다세대 전월세) — deposit + monthlyRent, mhouseNm + houseType
"""

from datetime import date
from pathlib import Path

import pytest

from mcp_server.domains.real_estate.normalizer import (
    AptRent,
    AptTrade,
    OffiRent,
    OffiTrade,
    RealEstateNormalizationError,
    RHRent,
    normalize_apt_rent,
    normalize_apt_trade,
    normalize_offi_rent,
    normalize_offi_trade,
    normalize_rh_rent,
)

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


def _load(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


# ─────────────────────────────────────────────
# AptTrade (아파트 매매) — 기존 테스트
# ─────────────────────────────────────────────

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


# ─────────────────────────────────────────────
# AptRent (아파트 전월세)
# ─────────────────────────────────────────────

def test_normalize_apt_rent_returns_rows():
    """정상 응답 → AptRent 리스트, 보증금/월세/전세 구분 검증."""
    result = normalize_apt_rent(_load("molit_apt_rent_sample.xml"), lawd_cd="11680")

    assert len(result) == 2
    assert all(isinstance(row, AptRent) for row in result)

    first = result[0]
    assert first.apt_name == "래미안"
    assert first.deposit == 70000
    assert first.monthly_rent == 0  # 전세
    assert first.deal_date == date(2024, 3, 20)
    assert first.area == 76.79
    assert first.dong == "대치동"
    assert first.build_year == 1979

    second = result[1]
    assert second.deposit == 20000
    assert second.monthly_rent == 120  # 월세


def test_normalize_apt_rent_handles_empty_and_error():
    assert normalize_apt_rent(_load("molit_apt_rent_empty.xml"), lawd_cd="11680") == []
    with pytest.raises(RealEstateNormalizationError):
        normalize_apt_rent(_load("molit_apt_rent_error.xml"), lawd_cd="11680")


# ─────────────────────────────────────────────
# OffiTrade (오피스텔 매매)
# ─────────────────────────────────────────────

def test_normalize_offi_trade_returns_rows():
    """offiNm 단지명 + dealAmount 가격 검증."""
    result = normalize_offi_trade(_load("molit_offi_trade_sample.xml"), lawd_cd="11680")

    assert len(result) == 2
    assert all(isinstance(row, OffiTrade) for row in result)

    first = result[0]
    assert first.offi_name == "강남 오피스텔"
    assert first.deal_amount == 27500
    assert first.deal_date == date(2024, 3, 20)
    assert first.dong == "청담동"


def test_normalize_offi_trade_handles_empty_and_error():
    assert normalize_offi_trade(_load("molit_offi_trade_empty.xml"), lawd_cd="11680") == []
    with pytest.raises(RealEstateNormalizationError):
        normalize_offi_trade(_load("molit_offi_trade_error.xml"), lawd_cd="11680")


# ─────────────────────────────────────────────
# OffiRent (오피스텔 전월세)
# ─────────────────────────────────────────────

def test_normalize_offi_rent_returns_rows():
    """offiNm + deposit/monthlyRent 검증."""
    result = normalize_offi_rent(_load("molit_offi_rent_sample.xml"), lawd_cd="11680")

    assert len(result) == 2
    assert all(isinstance(row, OffiRent) for row in result)

    first = result[0]
    assert first.offi_name == "강남 스카이홈"
    assert first.deposit == 16500
    assert first.monthly_rent == 10
    assert first.dong == "도곡동"


def test_normalize_offi_rent_handles_empty_and_error():
    assert normalize_offi_rent(_load("molit_offi_rent_empty.xml"), lawd_cd="11680") == []
    with pytest.raises(RealEstateNormalizationError):
        normalize_offi_rent(_load("molit_offi_rent_error.xml"), lawd_cd="11680")


# ─────────────────────────────────────────────
# RHRent (연립다세대 전월세)
# ─────────────────────────────────────────────

def test_normalize_rh_rent_returns_rows():
    """mhouseNm + houseType 보존 + deposit/monthlyRent."""
    result = normalize_rh_rent(_load("molit_rh_rent_sample.xml"), lawd_cd="11680")

    assert len(result) == 2
    assert all(isinstance(row, RHRent) for row in result)

    first = result[0]
    assert first.mhouse_name == "역삼삼성빌라"
    assert first.house_type == "다세대"
    assert first.deposit == 30500
    assert first.monthly_rent == 0  # 전세

    second = result[1]
    assert second.house_type == "연립"
    assert second.monthly_rent == 50  # 월세


def test_normalize_rh_rent_handles_empty_and_error():
    assert normalize_rh_rent(_load("molit_rh_rent_empty.xml"), lawd_cd="11680") == []
    with pytest.raises(RealEstateNormalizationError):
        normalize_rh_rent(_load("molit_rh_rent_error.xml"), lawd_cd="11680")
