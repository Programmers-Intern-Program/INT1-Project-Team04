"""normalize_law_info / normalize_bill_info 정규화 단위 테스트.

실제 응답 픽스처 (tests/data/law/*.xml) + 인공 케이스로 경계 조건 검증.
"""

from datetime import date
from pathlib import Path

import pytest

from mcp_server.domains.law.errors import LawNormalizationError
from mcp_server.domains.law.normalizer import (
    BillItem,
    LawItem,
    normalize_bill_info,
    normalize_law_info,
)

FIXTURES = Path(__file__).resolve().parents[2] / "data" / "law"
LAW_INFO_XML = (FIXTURES / "search_law_info.xml").read_text(encoding="utf-8")
BILL_INFO_XML = (FIXTURES / "search_bill_info.xml").read_text(encoding="utf-8")


# ─────────────────────────────────────────────
# normalize_law_info
# ─────────────────────────────────────────────


def test_normalize_law_info_real_fixture_returns_2_records():
    """법제처 픽스처 (totalCnt=2) → 2개 LawItem."""
    records = normalize_law_info(LAW_INFO_XML)
    assert len(records) == 2
    assert all(isinstance(r, LawItem) for r in records)


def test_normalize_law_info_extracts_korean_fields():
    """한글 태그(<법령명한글> 등) 가 정상 추출된다."""
    records = normalize_law_info(LAW_INFO_XML)
    first = records[0]
    assert first.law_name == "개인정보 보호법"
    assert first.law_no == "270351"
    assert first.law_id == "011357"
    assert first.law_kind == "법률"
    assert first.amend_kind == "일부개정"
    assert first.ministry_name == "개인정보보호위원회"
    assert first.current_status == "현행"


def test_normalize_law_info_parses_yyyymmdd_dates():
    """공포일자/시행일자 'YYYYMMDD' 가 date 로 파싱된다."""
    records = normalize_law_info(LAW_INFO_XML)
    first = records[0]
    assert first.promulgation_date == date(2025, 4, 1)
    assert first.enforcement_date == date(2025, 10, 2)


def test_normalize_law_info_empty_alias_is_none():
    """<법령약칭명> 빈 CDATA → None."""
    records = normalize_law_info(LAW_INFO_XML)
    assert records[0].law_alias is None


def test_normalize_law_info_raises_on_error_response():
    """resultCode != '00' → LawNormalizationError."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<LawSearch>
  <resultCode>99</resultCode>
  <resultMsg>인증 실패</resultMsg>
</LawSearch>"""
    with pytest.raises(LawNormalizationError) as exc:
        normalize_law_info(xml)
    assert "99" in str(exc.value)


def test_normalize_law_info_raises_on_invalid_xml():
    """XML 파싱 실패 → LawNormalizationError."""
    with pytest.raises(LawNormalizationError):
        normalize_law_info("<not><valid")


def test_normalize_law_info_returns_empty_list_when_no_law_nodes():
    """결과 0건 (법제처는 totalCnt=0 일 때 law 노드 없음) → 빈 리스트."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<LawSearch>
  <resultCode>00</resultCode>
  <resultMsg>success</resultMsg>
  <totalCnt>0</totalCnt>
</LawSearch>"""
    assert normalize_law_info(xml) == []


def test_normalize_law_info_invalid_yyyymmdd_becomes_none():
    """잘못된 8자리 일자(예: 99999999) → None, 예외 없음."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<LawSearch>
  <resultCode>00</resultCode>
  <resultMsg>success</resultMsg>
  <law id="1">
    <법령일련번호>1</법령일련번호>
    <법령ID>X</법령ID>
    <법령명한글>테스트법</법령명한글>
    <공포일자>99999999</공포일자>
  </law>
</LawSearch>"""
    records = normalize_law_info(xml)
    assert records[0].promulgation_date is None


def test_normalize_law_info_required_field_missing_raises():
    """법령명한글 누락 → LawNormalizationError."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<LawSearch>
  <resultCode>00</resultCode>
  <law id="1">
    <법령일련번호>1</법령일련번호>
    <법령ID>X</법령ID>
  </law>
</LawSearch>"""
    with pytest.raises(LawNormalizationError):
        normalize_law_info(xml)


# ─────────────────────────────────────────────
# normalize_bill_info
# ─────────────────────────────────────────────


def test_normalize_bill_info_real_fixture_returns_records():
    """의안 픽스처 (rows=5) → 5개 BillItem (또는 그 이상이면 모두)."""
    records = normalize_bill_info(BILL_INFO_XML)
    assert len(records) >= 1
    assert all(isinstance(r, BillItem) for r in records)


def test_normalize_bill_info_extracts_english_fields():
    """영문 태그(BILL_NO 등) 가 정상 추출된다."""
    records = normalize_bill_info(BILL_INFO_XML)
    first = records[0]
    assert first.bill_no == "2218679"
    assert first.bill_name == "소득세법 일부개정법률안"
    assert first.bill_id.startswith("PRC_")
    assert first.age == 22
    assert first.proposer == "김미애의원 등 14인"


def test_normalize_bill_info_parses_iso_date():
    """PROPOSE_DT 'YYYY-MM-DD' 가 date 로 파싱된다."""
    records = normalize_bill_info(BILL_INFO_XML)
    assert records[0].propose_date == date(2026, 4, 28)


def test_normalize_bill_info_empty_committee_is_none():
    """<COMMITTEE></COMMITTEE> 빈 노드 → None."""
    records = normalize_bill_info(BILL_INFO_XML)
    assert records[0].committee is None
    assert records[0].proc_result is None


def test_normalize_bill_info_raises_on_error_response():
    """RESULT/CODE != 'INFO-000' → LawNormalizationError."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <head>
    <RESULT>
      <CODE>INFO-200</CODE>
      <MESSAGE>해당하는 데이터가 없습니다.</MESSAGE>
    </RESULT>
  </head>
</root>"""
    with pytest.raises(LawNormalizationError) as exc:
        normalize_bill_info(xml)
    assert "INFO-200" in str(exc.value)


def test_normalize_bill_info_returns_empty_list_when_no_rows():
    """row 노드가 없으면 빈 리스트 (정상 응답 코드)."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <head>
    <list_total_count>0</list_total_count>
    <RESULT>
      <CODE>INFO-000</CODE>
      <MESSAGE>정상 처리되었습니다.</MESSAGE>
    </RESULT>
  </head>
</root>"""
    assert normalize_bill_info(xml) == []


def test_normalize_bill_info_required_field_missing_raises():
    """BILL_NAME 누락 → LawNormalizationError."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <head>
    <RESULT>
      <CODE>INFO-000</CODE>
    </RESULT>
  </head>
  <row>
    <BILL_ID>X</BILL_ID>
    <BILL_NO>1</BILL_NO>
  </row>
</root>"""
    with pytest.raises(LawNormalizationError):
        normalize_bill_info(xml)


def test_normalize_bill_info_invalid_date_becomes_none():
    """PROPOSE_DT 형식 어긋나면 None."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <head>
    <RESULT>
      <CODE>INFO-000</CODE>
    </RESULT>
  </head>
  <row>
    <BILL_ID>X</BILL_ID>
    <BILL_NO>1</BILL_NO>
    <BILL_NAME>테스트의안</BILL_NAME>
    <PROPOSE_DT>2026/04/28</PROPOSE_DT>
  </row>
</root>"""
    records = normalize_bill_info(xml)
    assert records[0].propose_date is None
