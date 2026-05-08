"""real_estate.region.resolve_lawd_cd 단위 테스트."""

import pytest

from mcp_server.domains.real_estate.errors import RealEstateRegionNotFoundError
from mcp_server.domains.real_estate.region import resolve_lawd_cd


def test_resolve_returns_code_when_input_is_5digit_code():
    """5자리 숫자는 등록된 코드면 그대로 반환."""
    assert resolve_lawd_cd("11680") == "11680"


def test_resolve_raises_for_unknown_code():
    """5자리 숫자지만 등록되지 않으면 RegionNotFoundError, candidates 비어있음."""
    with pytest.raises(RealEstateRegionNotFoundError) as excinfo:
        resolve_lawd_cd("99999")
    assert excinfo.value.candidates == []


def test_resolve_matches_unique_sigungu_alone():
    """시군구 단독 매칭이 1건이면 그대로 반환."""
    assert resolve_lawd_cd("강남구") == "11680"


def test_resolve_matches_sido_with_sigungu():
    """시도 + 시군구 정확 매칭."""
    assert resolve_lawd_cd("서울 강남구") == "11680"
    assert resolve_lawd_cd("서울특별시 강남구") == "11680"
    assert resolve_lawd_cd("경기 성남시 분당구") == "41135"


def test_resolve_normalizes_extra_whitespace():
    """공백/탭 다중 → 단일 공백으로 정규화."""
    assert resolve_lawd_cd("  서울   강남구  ") == "11680"


def test_resolve_raises_homonym_error_with_candidates():
    """'중구' 는 동음이의 → candidates 에 모든 후보 포함."""
    with pytest.raises(RealEstateRegionNotFoundError) as excinfo:
        resolve_lawd_cd("중구")
    candidates = excinfo.value.candidates
    assert len(candidates) > 1
    sidos = {label.split()[0] for label, _ in candidates}
    assert "서울특별시" in sidos
    assert "부산광역시" in sidos


def test_resolve_uses_sido_alias():
    """짧은 시도 alias 도 매칭. '전북' → '전라북도', '강원' → '강원특별자치도'."""
    assert resolve_lawd_cd("전북 전주시 완산구") == "45111"
    assert resolve_lawd_cd("강원 춘천시") == "51110"


def test_resolve_raises_when_sido_matches_but_sigungu_not_found():
    """시도는 매칭되는데 시군구가 안 맞으면 같은 시도 후보들이 candidates 에 들어간다."""
    with pytest.raises(RealEstateRegionNotFoundError) as excinfo:
        resolve_lawd_cd("서울 없는구")
    sidos = {label.split()[0] for label, _ in excinfo.value.candidates}
    assert sidos == {"서울특별시"}


def test_resolve_raises_when_no_match_at_all():
    """완전 매칭 실패 → candidates 비어있음."""
    with pytest.raises(RealEstateRegionNotFoundError) as excinfo:
        resolve_lawd_cd("없는지역명")
    assert excinfo.value.candidates == []


def test_resolve_raises_for_empty_input():
    with pytest.raises(RealEstateRegionNotFoundError):
        resolve_lawd_cd("   ")
