"""조달청 나라장터 입찰공고 응답 정규화.

api_source_service.fetch() 가 돌려준 JSON 본문(application/json 응답을 정렬
직렬화한 문자열) 을 BidNotice list 로 변환한다.

정규화는 도메인 책임이라 sources 계층 예외와 분리된 AuctionNormalizationError 사용.
"""

import json
from datetime import datetime

from pydantic import BaseModel, Field

from mcp_server.domains.auction.errors import AuctionNormalizationError

_SUCCESS_CODE = "00"
# G2B 일자 포맷 — 응답 항목별로 초 단위가 있을 때/없을 때 모두 관찰됨
_DATETIME_FORMATS = ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M")


class BidNotice(BaseModel):
    """나라장터 입찰공고 1건 (서비스용역·물품·공사 무관 공통)."""

    bid_no: str = Field(description="입찰공고번호 (bidNtceNo)")
    bid_ord: str = Field(description="공고차수 (bidNtceOrd, 예: '000')")
    bid_name: str = Field(description="공고명")
    notice_agency: str = Field(description="공고기관명 (ntceInsttNm)")
    demand_agency: str | None = Field(default=None, description="수요기관명 (dminsttNm)")
    notice_date: datetime = Field(description="공고일시 (bidNtceDt)")
    bid_begin_date: datetime | None = Field(default=None, description="입찰개시일시")
    bid_close_date: datetime | None = Field(default=None, description="입찰마감일시")
    opening_date: datetime | None = Field(default=None, description="개찰일시")
    estimated_price: int | None = Field(default=None, description="추정가격 (원 단위)")
    assigned_budget: int | None = Field(default=None, description="배정예산 (원 단위)")
    contract_method: str | None = Field(default=None, description="계약체결방법 (예: '제한경쟁')")
    bid_method: str | None = Field(default=None, description="입찰방식 (예: '전자입찰')")
    notice_kind: str | None = Field(default=None, description="공고종류 (등록공고/재공고 등)")
    bid_url: str | None = Field(default=None, description="공고 상세 URL")


def normalize_g2b_bid(json_text: str) -> list[BidNotice]:
    """G2B 입찰공고 JSON 응답 → list[BidNotice].

    Raises:
        AuctionNormalizationError: 응답 코드 비정상, JSON 파싱 실패, 필수 필드 누락.
    """
    items = _parse_items(json_text)
    return [_build_bid_notice(item) for item in items]


def _parse_items(json_text: str) -> list[dict]:
    """JSON 파싱 + resultCode 검증 + items 추출. 빈 응답은 빈 리스트."""
    try:
        payload = json.loads(json_text)
    except json.JSONDecodeError as exc:
        raise AuctionNormalizationError(f"G2B 응답 JSON 파싱 실패: {exc}") from exc

    response = payload.get("response") or {}
    header = response.get("header") or {}
    result_code = header.get("resultCode")
    if result_code != _SUCCESS_CODE:
        result_msg = header.get("resultMsg") or "(메시지 없음)"
        raise AuctionNormalizationError(
            f"G2B API 비정상 응답: code={result_code}, msg={result_msg}"
        )

    body = response.get("body") or {}
    items = body.get("items") or []
    # 단건 응답에서 dict 가 직접 올 가능성 대비
    if isinstance(items, dict):
        items = [items]
    if not isinstance(items, list):
        raise AuctionNormalizationError(
            f"G2B 응답 body.items 형식 비정상: {type(items).__name__}"
        )
    return items


def _build_bid_notice(item: dict) -> BidNotice:
    try:
        return BidNotice(
            bid_no=_required(item, "bidNtceNo"),
            bid_ord=_required(item, "bidNtceOrd"),
            bid_name=_required(item, "bidNtceNm"),
            notice_agency=_required(item, "ntceInsttNm"),
            demand_agency=_optional_str(item, "dminsttNm"),
            notice_date=_required_datetime(item, "bidNtceDt"),
            bid_begin_date=_optional_datetime(item, "bidBeginDt"),
            bid_close_date=_optional_datetime(item, "bidClseDt"),
            opening_date=_optional_datetime(item, "opengDt"),
            estimated_price=_optional_int(item, "presmptPrce"),
            assigned_budget=_optional_int(item, "asignBdgtAmt"),
            contract_method=_optional_str(item, "cntrctCnclsMthdNm"),
            bid_method=_optional_str(item, "bidMethdNm"),
            notice_kind=_optional_str(item, "ntceKindNm"),
            bid_url=_optional_str(item, "bidNtceUrl"),
        )
    except (KeyError, ValueError) as exc:
        raise AuctionNormalizationError(f"item 필수 필드 파싱 실패: {exc}") from exc


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


def _optional_int(item: dict, key: str) -> int | None:
    """G2B 가격/예산은 콤마 없는 정수 문자열이지만 콤마 가능성 방어."""
    value = item.get(key)
    if value is None:
        return None
    text = str(value).replace(",", "").strip()
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def _parse_datetime(value: str) -> datetime:
    for fmt in _DATETIME_FORMATS:
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            continue
    raise ValueError(f"datetime 파싱 실패: {value!r}")


def _required_datetime(item: dict, key: str) -> datetime:
    raw = _required(item, key)
    return _parse_datetime(raw)


def _optional_datetime(item: dict, key: str) -> datetime | None:
    value = item.get(key)
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    try:
        return _parse_datetime(raw)
    except ValueError:
        return None


__all__ = [
    "AuctionNormalizationError",
    "BidNotice",
    "normalize_g2b_bid",
]
