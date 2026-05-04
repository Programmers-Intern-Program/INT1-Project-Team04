"""normalize_g2b_bid 정규화 단위 테스트.

실제 G2B 응답 픽스처(tests/data/auction/search_g2b_bid.json) 와 인공 케이스로
경계 조건 검증.
"""

import json
from datetime import datetime
from pathlib import Path

import pytest

from mcp_server.domains.auction.errors import AuctionNormalizationError
from mcp_server.domains.auction.normalizer import BidNotice, normalize_g2b_bid

FIXTURES = Path(__file__).resolve().parents[2] / "data" / "auction"
SAMPLE_JSON = (FIXTURES / "search_g2b_bid.json").read_text(encoding="utf-8")


def _wrap_items(items: list[dict]) -> str:
    return json.dumps(
        {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "정상"},
                "body": {
                    "items": items,
                    "numOfRows": len(items),
                    "pageNo": 1,
                    "totalCount": len(items),
                },
            }
        }
    )


def _wrap_error(code: str = "99", msg: str = "에러") -> str:
    return json.dumps(
        {
            "response": {
                "header": {"resultCode": code, "resultMsg": msg},
                "body": {"items": [], "numOfRows": 0, "pageNo": 1, "totalCount": 0},
            }
        }
    )


def test_normalize_real_fixture_returns_5_records():
    """실제 G2B 응답 픽스처(5건) → 5개 BidNotice."""
    records = normalize_g2b_bid(SAMPLE_JSON)
    assert len(records) == 5
    assert all(isinstance(r, BidNotice) for r in records)


def test_normalize_required_fields_extracted():
    """첫 번째 항목의 핵심 필드가 추출된다."""
    records = normalize_g2b_bid(SAMPLE_JSON)
    first = records[0]
    assert first.bid_no
    assert first.bid_name
    assert first.notice_agency
    assert isinstance(first.notice_date, datetime)


def test_normalize_estimated_price_parsed_as_int():
    """presmptPrce 문자열이 int 로 파싱된다."""
    records = normalize_g2b_bid(SAMPLE_JSON)
    has_price = [r for r in records if r.estimated_price is not None]
    assert has_price, "픽스처에 추정가격 있는 항목이 없음"
    for r in has_price:
        assert isinstance(r.estimated_price, int)
        assert r.estimated_price > 0


def test_normalize_empty_items_returns_empty_list():
    """items 가 비어있어도 예외 없이 빈 리스트 반환."""
    assert normalize_g2b_bid(_wrap_items([])) == []


def test_normalize_raises_on_error_response():
    """resultCode != '00' → AuctionNormalizationError."""
    with pytest.raises(AuctionNormalizationError) as exc:
        normalize_g2b_bid(_wrap_error("99", "서비스 키 오류"))
    assert "99" in str(exc.value)


def test_normalize_raises_on_invalid_json():
    """JSON 파싱 실패 → AuctionNormalizationError."""
    with pytest.raises(AuctionNormalizationError):
        normalize_g2b_bid("not a json")


def test_normalize_optional_dates_handle_empty_string():
    """bidBeginDt 가 빈 문자열인 항목도 정규화 가능 (None 으로)."""
    item = {
        "bidNtceNo": "R26BK000001",
        "bidNtceOrd": "000",
        "bidNtceNm": "테스트 공고",
        "ntceInsttNm": "테스트 기관",
        "bidNtceDt": "2026-04-22 13:36:01",
        "bidBeginDt": "",
        "bidClseDt": "",
        "opengDt": "",
    }
    records = normalize_g2b_bid(_wrap_items([item]))
    assert records[0].bid_begin_date is None
    assert records[0].bid_close_date is None
    assert records[0].opening_date is None


def test_normalize_required_field_missing_raises():
    """bidNtceNo 누락 → AuctionNormalizationError."""
    item = {
        "bidNtceOrd": "000",
        "bidNtceNm": "테스트 공고",
        "ntceInsttNm": "테스트 기관",
        "bidNtceDt": "2026-04-22 13:36:01",
    }
    with pytest.raises(AuctionNormalizationError):
        normalize_g2b_bid(_wrap_items([item]))


def test_normalize_supports_minute_only_datetime_format():
    """일자가 'YYYY-MM-DD HH:MM' (초 누락) 형태여도 파싱."""
    item = {
        "bidNtceNo": "R26BK000002",
        "bidNtceOrd": "000",
        "bidNtceNm": "테스트 공고",
        "ntceInsttNm": "테스트 기관",
        "bidNtceDt": "2026-04-22 13:36",
        "bidQlfctRgstDt": "2026-05-06 18:00",
    }
    records = normalize_g2b_bid(_wrap_items([item]))
    assert records[0].notice_date == datetime(2026, 4, 22, 13, 36)


def test_normalize_single_dict_item_treated_as_list():
    """body.items 가 dict 단건이어도 리스트로 정규화."""
    item = {
        "bidNtceNo": "R26BK000003",
        "bidNtceOrd": "000",
        "bidNtceNm": "단건 공고",
        "ntceInsttNm": "테스트 기관",
        "bidNtceDt": "2026-04-22 13:36:01",
    }
    payload = json.dumps(
        {
            "response": {
                "header": {"resultCode": "00", "resultMsg": "정상"},
                "body": {"items": item, "numOfRows": 1, "pageNo": 1, "totalCount": 1},
            }
        }
    )
    records = normalize_g2b_bid(payload)
    assert len(records) == 1
    assert records[0].bid_no == "R26BK000003"
