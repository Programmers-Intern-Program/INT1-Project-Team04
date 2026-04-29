"""11개 MCP 도구 실제 공공 API 통합 호출 검증.

목적:
- Spring AI 가 MCP 프로토콜로 호출했을 때 도달하는 종단점이 정상 동작하는지 확인.
- LLM 의 도구 선택 변동성을 제거하기 위해 본 스크립트는 도구 함수를 직접 호출
  (FastMCP 인스턴스를 통한 자연어 호출 X).
- 결과는 도구별 status / 응답 길이 / 에러 메시지로 요약.

사용법:
    cd mcp-server
    uv run python scripts/probe_all_tools.py

선행 조건:
    .env 에 11개 키 모두 설정. seed_*_source.py 3종 모두 실행 완료 (api_source row 등록).
"""

import asyncio
import sys
import traceback
from typing import Any, Awaitable, Callable

from mcp_server.db.session import reset_engine
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


async def _call(
    tool_func: Callable[[Any], Awaitable[dict[str, Any]]],
    tool_name: str,
    payload: Any,
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
        return {
            "tool": tool_name,
            "status": "ok",
            "note": note,
            "text": result.get("text", "")[:120],
        }
    except Exception as exc:
        return {
            "tool": tool_name,
            "status": "error",
            "note": f"{type(exc).__name__}: {exc}",
            "text": "",
            "trace": traceback.format_exc(limit=3),
        }


async def main() -> None:
    """순차 호출 (외부 API 부하 방지)."""
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
            SearchBillInfoInput(age="22", num_of_rows=5),
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

    print(f"=== {EXPECTED_TOOL_COUNT}개 MCP 도구 통합 호출 ===\n", file=sys.stderr)
    results: list[dict[str, Any]] = []
    for tool_name, func, payload in calls:
        print(f"[..] {tool_name} 호출 중...", file=sys.stderr)
        r = await _call(func, tool_name, payload)
        results.append(r)
        prefix = "[OK]" if r["status"] == "ok" else "[ERR]"
        print(f"{prefix} {tool_name}: {r['note']}", file=sys.stderr)
        if r["status"] == "ok" and r.get("text"):
            print(f"     text={r['text']!r}", file=sys.stderr)
        print(file=sys.stderr)

    # 요약
    ok = sum(1 for r in results if r["status"] == "ok")
    err = len(results) - ok
    print("=" * 50, file=sys.stderr)
    print(f"[SUMMARY] OK {ok}/{len(results)}, ERROR {err}", file=sys.stderr)
    for r in results:
        symbol = "✓" if r["status"] == "ok" else "✗"
        print(f"  {symbol} {r['tool']}: {r['note']}", file=sys.stderr)

    await reset_engine()
    sys.exit(0 if err == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
