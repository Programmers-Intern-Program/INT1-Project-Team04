"""11개 MCP 도구 실제 공공 API 통합 호출 검증.

목적:
- Spring AI 가 MCP 프로토콜로 호출했을 때 도달하는 종단점이 정상 동작하는지 확인.
- LLM 의 도구 선택 변동성을 제거하기 위해 본 스크립트는 도구 함수를 직접 호출
  (FastMCP 인스턴스를 통한 자연어 호출 X).
- 결과는 도구별 status / 응답 길이 / 에러 메시지로 요약.

사용법:
    cd mcp-server
    uv run python scripts/probe_all_tools.py
    uv run python scripts/probe_all_tools.py --simulate-failure

선행 조건:
    .env 에 11개 키 모두 설정. seed_*_source.py 3종 모두 실행 완료 (api_source row 등록).

simulate-failure 모드:
- 외부 API 호출(`api_source_service._call_external_api`)을 monkey-patch 로 강제 실패시켜
  `fetch()` 의 캐시 폴백 분기(SourceFetchError catch → _load_cache_by_site_url)를 점검한다.
- 운영 흐름:
  1) 정상 모드 1회 → 캐시 적재
  2) --simulate-failure → 11개 모두 cache_hit 확인
- 캐시 miss 가 1건이라도 있으면 exit 2.
"""

import argparse
import asyncio
import sys
import traceback
from typing import Any, Awaitable, Callable

from mcp_server.db.session import reset_engine
from mcp_server.sources import api_source_service as _api_svc
from mcp_server.sources.errors import SourceFetchError
from mcp_server.sources.result import RawResult
from mcp_server.tools.auction import SearchG2bBidInput, search_g2b_bid
from mcp_server.tools.jobs import (
    SearchPublicJobInput,
    SearchWorknetJobInput,
    search_public_job,
    search_worknet_job,
)
from mcp_server.tools.law import (
    SearchBillInfoInput,
    SearchLawInfoInput,
    search_bill_info,
    search_law_info,
)
from mcp_server.tools.real_estate import (
    MolitRealEstateInput,
    search_apt_rent,
    search_house_price,
    search_offi_rent,
    search_offi_trade,
    search_rh_rent,
    search_rh_trade,
)

# 도구 수 변경 시 이 상수만 갱신.
EXPECTED_TOOL_COUNT = 11

# 부동산 도구 6종 공통 입력 (서울 강남구 = LAWD_CD 11680, 거래연월 202602).
_RE_INPUT = MolitRealEstateInput(region="11680", deal_ymd="202602")

# fetch() 호출별 캐시 폴백 결과 캡처. simulate-failure 모드에서만 사용.
# 도구당 fetch() 1회 호출 가정 — tool_name 으로 마지막 호출 결과 저장.
_cache_log: dict[str, dict[str, Any]] = {}


def _install_failure_simulation() -> None:
    """외부 호출 강제 실패 + fetch() wrap 으로 캐시 폴백 결과 캡처."""

    async def _always_fail(endpoint: str, params: dict, http_client: Any) -> str:
        raise SourceFetchError(f"[SIMULATED] {endpoint}")

    _api_svc._call_external_api = _always_fail  # type: ignore[assignment]

    original_fetch = _api_svc.fetch

    async def _patched_fetch(
        source_id: int,
        params: dict | None = None,
        *,
        _test_http_client: Any = None,
    ) -> RawResult:
        raw = await original_fetch(
            source_id, params, _test_http_client=_test_http_client
        )
        tool_name = raw.raw_metadata.get("tool_name", f"source_id={source_id}")
        # fetch_error 키 존재 여부로 캐시 hit/miss 판정.
        # api_source_service.fetch() 는 캐시 hit 시 fetch_error 를 raw_metadata 에 안 넣고,
        # 캐시 miss 시에만 fetch_error 를 박는다.
        if "fetch_error" in raw.raw_metadata:
            _cache_log[tool_name] = {
                "status": "miss",
                "cached_at": None,
                "error": raw.raw_metadata.get("fetch_error", ""),
            }
        else:
            _cache_log[tool_name] = {
                "status": "hit",
                "cached_at": raw.fetched_at.isoformat() if raw.fetched_at else None,
                "error": None,
            }
        return raw

    _api_svc.fetch = _patched_fetch  # type: ignore[assignment]


async def _call(
    tool_func: Callable[[Any], Awaitable[dict[str, Any]]],
    tool_name: str,
    payload: Any,
    *,
    simulate_failure: bool,
) -> dict[str, Any]:
    """단일 도구 1회 호출. 예외는 catch 후 status='error' 로 보고."""
    try:
        result = await tool_func(payload)
        raw_len = result.get("structured", {}).get("raw_length")
        # 부동산 도구는 raw_length 가 없고 raw_count 존재.
        if raw_len is None:
            raw_count = result.get("metadata", {}).get("raw_count")
            note = f"raw_count={raw_count}"
        else:
            note = f"raw_length={raw_len}"
        cache_info = _cache_log.get(tool_name) if simulate_failure else None
        return {
            "tool": tool_name,
            "status": "ok",
            "note": note,
            "text": result.get("text", "")[:120],
            "cache": cache_info,
        }
    except Exception as exc:
        cache_info = _cache_log.get(tool_name) if simulate_failure else None
        return {
            "tool": tool_name,
            "status": "error",
            "note": f"{type(exc).__name__}: {exc}",
            "text": "",
            "trace": traceback.format_exc(limit=3),
            "cache": cache_info,
        }


def _format_cache(cache: dict[str, Any] | None) -> str:
    if cache is None:
        return ""
    if cache["status"] == "hit":
        return f" [cache_hit, cached_at={cache['cached_at']}]"
    return " [cache_miss]"


async def main(simulate_failure: bool) -> None:
    """순차 호출 (외부 API 부하 방지)."""
    if simulate_failure:
        _install_failure_simulation()

    calls: list[tuple[str, Callable[[Any], Awaitable[dict[str, Any]]], Any]] = [
        # 부동산 6종
        ("search_house_price", search_house_price, _RE_INPUT),
        ("search_apt_rent", search_apt_rent, _RE_INPUT),
        ("search_offi_trade", search_offi_trade, _RE_INPUT),
        ("search_offi_rent", search_offi_rent, _RE_INPUT),
        ("search_rh_rent", search_rh_rent, _RE_INPUT),
        ("search_rh_trade", search_rh_trade, _RE_INPUT),
        # 법률 2종
        (
            "search_law_info",
            search_law_info,
            SearchLawInfoInput(query="개인정보 보호법", num_of_rows=5),
        ),
        (
            "search_bill_info",
            search_bill_info,
            SearchBillInfoInput(age=22, num_of_rows=5),
        ),
        # 채용 2종
        (
            "search_public_job",
            search_public_job,
            SearchPublicJobInput(num_of_rows=5),
        ),
        (
            "search_worknet_job",
            search_worknet_job,
            SearchWorknetJobInput(display=5),
        ),
        # 경매 1종
        ("search_g2b_bid", search_g2b_bid, SearchG2bBidInput(num_of_rows=5)),
    ]

    if len(calls) != EXPECTED_TOOL_COUNT:
        raise RuntimeError(
            f"도구 호출 plan 개수 불일치: expected={EXPECTED_TOOL_COUNT}, got={len(calls)}"
        )

    mode_label = "SIMULATE-FAILURE" if simulate_failure else "NORMAL"
    print(
        f"=== {EXPECTED_TOOL_COUNT}개 MCP 도구 통합 호출 [{mode_label}] ===\n",
        file=sys.stderr,
    )
    results: list[dict[str, Any]] = []
    for tool_name, func, payload in calls:
        print(f"[..] {tool_name} 호출 중...", file=sys.stderr)
        r = await _call(func, tool_name, payload, simulate_failure=simulate_failure)
        results.append(r)
        prefix = "[OK]" if r["status"] == "ok" else "[ERR]"
        cache_suffix = _format_cache(r.get("cache"))
        print(f"{prefix} {tool_name}: {r['note']}{cache_suffix}", file=sys.stderr)
        if r["status"] == "ok" and r.get("text"):
            print(f"     text={r['text']!r}", file=sys.stderr)
        elif r["status"] == "error" and r.get("trace"):
            for line in r["trace"].rstrip().splitlines():
                print(f"     {line}", file=sys.stderr)
        print(file=sys.stderr)

    # 요약
    ok = sum(1 for r in results if r["status"] == "ok")
    err = len(results) - ok
    print("=" * 50, file=sys.stderr)
    print(f"[SUMMARY] OK {ok}/{len(results)}, ERROR {err}", file=sys.stderr)
    if simulate_failure:
        cache_hit = sum(
            1 for r in results if r.get("cache") and r["cache"]["status"] == "hit"
        )
        cache_miss = sum(
            1 for r in results if r.get("cache") and r["cache"]["status"] == "miss"
        )
        cache_unknown = len(results) - cache_hit - cache_miss
        print(
            f"[CACHE]   hit {cache_hit}, miss {cache_miss}, unknown {cache_unknown}",
            file=sys.stderr,
        )
    for r in results:
        symbol = "✓" if r["status"] == "ok" else "✗"
        cache_suffix = _format_cache(r.get("cache"))
        print(f"  {symbol} {r['tool']}: {r['note']}{cache_suffix}", file=sys.stderr)

    await reset_engine()
    # exit code:
    # - 정상 모드: ERROR 0건이면 0, 아니면 1
    # - simulate-failure: cache miss 0건이면 0, 아니면 2 (운영 전 차단 신호)
    if simulate_failure:
        miss = sum(
            1 for r in results if r.get("cache") and r["cache"]["status"] == "miss"
        )
        unknown = sum(
            1 for r in results if r.get("cache") is None
        )
        sys.exit(0 if miss == 0 and unknown == 0 else 2)
    sys.exit(0 if err == 0 else 1)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="MCP 도구 11개 실제 호출 검증 + 캐시 폴백 점검"
    )
    parser.add_argument(
        "--simulate-failure",
        action="store_true",
        help=(
            "외부 API 호출을 강제 실패시켜 캐시 폴백 동작을 점검한다. "
            "사전에 정상 모드로 캐시가 적재되어 있어야 모두 cache_hit 가 됨."
        ),
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    asyncio.run(main(simulate_failure=args.simulate_failure))
