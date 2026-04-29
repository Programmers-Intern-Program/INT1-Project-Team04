"""법률/채용/경매 5개 도구 raw 응답 본문을 픽스처로 저장.

목적:
- 정규화 함수 작성 전에 외부 공공 API 의 실제 응답 본문 구조를 확보.
- probe_all_tools.py 가 제공하는 요약(raw_length / raw_count) 만으로는
  정규화 모델 설계 불가 → 본문 자체가 필요.

저장 경로:
    tests/data/{law,jobs,auction}/{tool_name}.{xml|json}

확장자 결정:
    api_source_service.fetch() 가 application/json content-type 일 때 json.dumps 로
    정렬된 JSON 문자열을 반환하므로, 본문 첫 글자가 '{' 또는 '[' 면 .json,
    아니면 .xml 로 저장.

사용법:
    cd mcp-server
    uv run python scripts/dump_raw_responses.py

선행 조건:
    .env 5개 키 설정 (MOLEG_LAW_INFO_API_KEY, NA_BILL_INFO_API_KEY,
    MOEF_PUBLIC_JOB_API_KEY, KEIS_WORKNET_JOB_API_KEY, PPS_G2B_BID_API_KEY).
    seed_law_source.py / seed_jobs_source.py / seed_auction_source.py 실행 완료.
"""

import asyncio
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from mcp_server.config import get_settings
from mcp_server.db.session import reset_engine
from mcp_server.sources import api_source_service

# 프로젝트 루트 기준 픽스처 디렉토리 (mcp-server/tests/data/)
_THIS = Path(__file__).resolve()
_FIXTURE_ROOT = _THIS.parent.parent / "tests" / "data"


def _ext_for(content: str) -> str:
    """본문 첫 비공백 문자로 .json / .xml 결정."""
    stripped = content.lstrip()
    if stripped.startswith(("{", "[")):
        return "json"
    return "xml"


def _yyyymmddhhmm(dt: datetime) -> str:
    return dt.strftime("%Y%m%d%H%M")


async def _build_calls() -> list[tuple[str, str, dict[str, Any]]]:
    """(domain, tool_name, params) 5건 생성. 도구 모듈의 기본값과 일치시킨다."""
    settings = get_settings()
    now = datetime.now()

    return [
        (
            "law",
            "search_law_info",
            {
                "serviceKey": settings.moleg_law_info_api_key,
                "target": "law",
                "query": "개인정보 보호법",
                "numOfRows": 5,
                "pageNo": 1,
            },
        ),
        (
            "law",
            "search_bill_info",
            {
                "KEY": settings.na_bill_info_api_key,
                "Type": "xml",
                "pIndex": 1,
                "pSize": 5,
                "AGE": 22,
            },
        ),
        (
            "jobs",
            "search_public_job",
            {
                "serviceKey": settings.moef_public_job_api_key,
                "pageNo": 1,
                "numOfRows": 5,
                "resultType": "json",
            },
        ),
        (
            "jobs",
            "search_worknet_job",
            {
                "authKey": settings.keis_worknet_job_api_key,
                "callTp": "L",
                "returnType": "XML",
                "startPage": 1,
                "display": 5,
            },
        ),
        (
            "auction",
            "search_g2b_bid",
            {
                "serviceKey": settings.pps_g2b_bid_api_key,
                "pageNo": 1,
                "numOfRows": 5,
                "type": "json",
                "inqryDiv": 1,
                "inqryBgnDt": _yyyymmddhhmm(now - timedelta(days=7)),
                "inqryEndDt": _yyyymmddhhmm(now),
            },
        ),
    ]


async def _dump_one(domain: str, tool_name: str, params: dict[str, Any]) -> Path:
    source_id = await api_source_service.resolve_source_id_by_tool_name(tool_name)
    raw = await api_source_service.fetch(source_id=source_id, params=params)
    content = raw.content or ""
    ext = _ext_for(content)
    out_dir = _FIXTURE_ROOT / domain
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{tool_name}.{ext}"
    out_path.write_text(content, encoding="utf-8")
    return out_path


async def main() -> None:
    print("=== 5개 도구 raw 응답 캡처 ===\n", file=sys.stderr)
    calls = await _build_calls()
    results: list[tuple[str, Path, int]] = []
    for domain, tool_name, params in calls:
        print(f"[..] {tool_name} 호출 중...", file=sys.stderr)
        try:
            path = await _dump_one(domain, tool_name, params)
            size = path.stat().st_size
            print(f"[OK] {tool_name}: {path.relative_to(_FIXTURE_ROOT.parent.parent)} ({size}B)", file=sys.stderr)
            results.append((tool_name, path, size))
        except Exception as exc:
            print(f"[ERR] {tool_name}: {type(exc).__name__}: {exc}", file=sys.stderr)
        print(file=sys.stderr)

    print("=" * 50, file=sys.stderr)
    print(f"[SUMMARY] saved {len(results)}/{len(calls)}", file=sys.stderr)
    for tool_name, path, size in results:
        print(f"  - {tool_name}: {path.name} ({size}B)", file=sys.stderr)

    await reset_engine()
    sys.exit(0 if len(results) == len(calls) else 1)


if __name__ == "__main__":
    asyncio.run(main())
