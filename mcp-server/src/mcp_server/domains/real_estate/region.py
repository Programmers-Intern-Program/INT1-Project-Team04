"""자연어 region 입력 → LAWD_CD 5자리 코드 변환.

MOLIT 실거래가 API 는 시군구 단위(법정동코드 앞 5자리)만 받는다. 따라서 동·읍·면
입력은 지원하지 않으며, "강남구", "서울 중구", "성남시 분당구" 처럼 시군구 단위까지만
인식한다.

동음이의 시('중구', '동구', '서구' 등)에는 자동 선택을 하지 않고 후보를 담아 예외를
던진다. 호출자(AI) 가 시도와 함께 재입력하도록 한다.
"""

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import TypedDict

from mcp_server.domains.real_estate.errors import RealEstateRegionNotFoundError

_DATA_PATH = Path(__file__).resolve().parents[3].parent / "data" / "lawd_codes.json"
_CODE_RE = re.compile(r"^[0-9]{5}$")
_WS_RE = re.compile(r"\s+")

# 사용자가 자주 쓰는 짧은 시도명 → 정식 명칭 매핑.
# 정식 명칭 자체도 키로 포함해 정규화 결과가 멱등이 되도록 한다.
_SIDO_ALIAS: dict[str, tuple[str, ...]] = {
    "서울특별시": ("서울특별시", "서울시", "서울"),
    "부산광역시": ("부산광역시", "부산시", "부산"),
    "대구광역시": ("대구광역시", "대구시", "대구"),
    "인천광역시": ("인천광역시", "인천시", "인천"),
    "광주광역시": ("광주광역시", "광주시", "광주"),
    "대전광역시": ("대전광역시", "대전시", "대전"),
    "울산광역시": ("울산광역시", "울산시", "울산"),
    "세종특별자치시": ("세종특별자치시", "세종시", "세종"),
    "경기도": ("경기도", "경기"),
    "충청북도": ("충청북도", "충북"),
    "충청남도": ("충청남도", "충남"),
    "전라북도": ("전라북도", "전북특별자치도", "전북"),
    "전라남도": ("전라남도", "전남"),
    "경상북도": ("경상북도", "경북"),
    "경상남도": ("경상남도", "경남"),
    "제주특별자치도": ("제주특별자치도", "제주도", "제주"),
    "강원특별자치도": ("강원특별자치도", "강원도", "강원"),
}


class _LawdRow(TypedDict):
    code: str
    sido: str
    sigungu: str


@lru_cache(maxsize=1)
def _load_lawd_codes() -> list[_LawdRow]:
    with _DATA_PATH.open(encoding="utf-8") as fp:
        return json.load(fp)


def _alias_to_canonical_sido(token: str) -> str | None:
    """짧은 시도명/정식명 → 정식 시도명. 매칭 실패 시 None."""
    for canonical, aliases in _SIDO_ALIAS.items():
        if token in aliases:
            return canonical
    return None


def _candidates_label(rows: list[_LawdRow]) -> list[tuple[str, str]]:
    return [(f"{r['sido']} {r['sigungu']}", r["code"]) for r in rows]


def resolve_lawd_cd(query: str) -> str:
    """자연어 region 또는 5자리 코드 → LAWD_CD 5자리 코드.

    매칭 우선순위:
      1. 입력이 5자리 숫자 → 등록된 코드인지 검증 후 그대로 반환
      2. 시도 + 시군구 (예: "서울 강남구", "경기 성남시 분당구") → 정확 매칭
      3. 시군구 단독 (예: "강남구") → 1건이면 반환, 2건+ 이면 동음이의 에러

    Raises:
        RealEstateRegionNotFoundError: 매칭 실패 또는 동음이의(자동 선택 거부).
            동음이의일 경우 `candidates` 에 후보가 채워진다.
    """
    rows = _load_lawd_codes()

    # (1) 코드 직통
    if _CODE_RE.match(query):
        for r in rows:
            if r["code"] == query:
                return query
        raise RealEstateRegionNotFoundError(
            f"등록되지 않은 LAWD_CD: {query}", candidates=[]
        )

    # 공백 정규화
    normalized = _WS_RE.sub(" ", query.strip())
    if not normalized:
        raise RealEstateRegionNotFoundError("region 이 비어있음", candidates=[])

    # (2) 시도 + 시군구
    parts = normalized.split(" ", 1)
    if len(parts) == 2:
        canonical_sido = _alias_to_canonical_sido(parts[0])
        if canonical_sido is not None:
            sigungu = parts[1].strip()
            for r in rows:
                if r["sido"] == canonical_sido and r["sigungu"] == sigungu:
                    return r["code"]
            # 시도가 매칭됐는데 시군구가 안 맞으면 같은 시도 내 후보를 보여준다
            same_sido = [r for r in rows if r["sido"] == canonical_sido]
            raise RealEstateRegionNotFoundError(
                f"'{canonical_sido}' 안에 '{sigungu}' 시군구 없음",
                candidates=_candidates_label(same_sido),
            )

    # (3) 시군구 단독
    matches = [r for r in rows if r["sigungu"] == normalized]
    if len(matches) == 1:
        return matches[0]["code"]
    if len(matches) > 1:
        raise RealEstateRegionNotFoundError(
            f"'{normalized}' 동음이의: 시도와 함께 다시 입력하세요 (예: '서울 {normalized}')",
            candidates=_candidates_label(matches),
        )

    raise RealEstateRegionNotFoundError(
        f"'{query}' 매칭되는 시군구 없음", candidates=[]
    )


__all__ = ["resolve_lawd_cd"]
