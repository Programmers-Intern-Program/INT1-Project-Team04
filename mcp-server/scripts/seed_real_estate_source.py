"""부동산 도메인 api_source row 등록.

국토교통부 부동산 실거래가 5종 자료유형 메타데이터를 mcp 내부 DB 의
api_source 테이블에 시드한다.
멱등: 각 row 는 tool_name 기준으로 존재하면 update, 없으면 insert.

서비스 키(serviceKey) 는 param_schema 에 포함하지 않는다.
도구 레이어가 settings.molit_*_api_key 를 로드해 호출 시점에 합성하는 책임.

사용법:
    cd mcp-server
    uv run python scripts/seed_real_estate_source.py
"""

import asyncio
import sys

from sqlalchemy import select

from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session, reset_engine

# 5종 모두 동일한 호출 파라미터 스키마 (LAWD_CD + DEAL_YMD + 페이지네이션).
# 자료유형마다 응답 XML 필드는 다르지만, 요청 인자는 MOLIT 공통.
_COMMON_PARAM_SCHEMA: dict = {
    "type": "object",
    "required": ["LAWD_CD", "DEAL_YMD"],
    "properties": {
        "LAWD_CD": {
            "type": "string",
            "pattern": "^[0-9]{5}$",
            "description": "법정동 코드 5자리 (시군구 단위, 예: 강남구=11680)",
        },
        "DEAL_YMD": {
            "type": "string",
            "pattern": "^[0-9]{6}$",
            "description": "거래연월 YYYYMM (예: 202403)",
        },
        "pageNo": {"type": "integer", "minimum": 1, "default": 1},
        "numOfRows": {
            "type": "integer",
            "minimum": 1,
            "maximum": 1000,
            "default": 100,
        },
    },
}

_BASE_URL = "https://apis.data.go.kr/1613000"

# tool_name 은 추후 추가될 MCP 도구 함수명과 1:1 매핑.
# (현재는 search_house_price 만 도구로 등록돼 있음 — 나머지는 도구 추가와 함께 활성화)
SOURCES: list[dict] = [
    {
        "tool_name": "search_house_price",
        "name": "국토교통부 아파트 매매 실거래가",
        "url_template": f"{_BASE_URL}/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev",
        "param_schema": _COMMON_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_apt_rent",
        "name": "국토교통부 아파트 전월세 실거래가",
        "url_template": f"{_BASE_URL}/RTMSDataSvcAptRent/getRTMSDataSvcAptRent",
        "param_schema": _COMMON_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_offi_trade",
        "name": "국토교통부 오피스텔 매매 실거래가",
        "url_template": f"{_BASE_URL}/RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade",
        "param_schema": _COMMON_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_offi_rent",
        "name": "국토교통부 오피스텔 전월세 실거래가",
        "url_template": f"{_BASE_URL}/RTMSDataSvcOffiRent/getRTMSDataSvcOffiRent",
        "param_schema": _COMMON_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_rh_rent",
        "name": "국토교통부 연립다세대 전월세 실거래가",
        "url_template": f"{_BASE_URL}/RTMSDataSvcRHRent/getRTMSDataSvcRHRent",
        "param_schema": _COMMON_PARAM_SCHEMA,
    },
]


async def _upsert_source(spec: dict) -> None:
    """단일 자료유형 upsert. tool_name 기준 멱등."""
    async with get_session() as session:
        existing = await session.execute(
            select(ApiSource).where(ApiSource.tool_name == spec["tool_name"])
        )
        row = existing.scalar_one_or_none()

        if row is None:
            row = ApiSource(
                tool_name=spec["tool_name"],
                name=spec["name"],
                url_template=spec["url_template"],
                param_schema=spec["param_schema"],
            )
            session.add(row)
            await session.commit()
            await session.refresh(row)
            print(
                f"[+] api_source insert: id={row.id}, tool_name={spec['tool_name']}",
                file=sys.stderr,
            )
        else:
            row.name = spec["name"]
            row.url_template = spec["url_template"]
            row.param_schema = spec["param_schema"]
            await session.commit()
            await session.refresh(row)
            print(
                f"[~] api_source update: id={row.id}, tool_name={spec['tool_name']}",
                file=sys.stderr,
            )


async def seed() -> None:
    for spec in SOURCES:
        await _upsert_source(spec)
    print(f"[OK] seeded {len(SOURCES)} api_source row(s)", file=sys.stderr)
    await reset_engine()


def main() -> None:
    asyncio.run(seed())


if __name__ == "__main__":
    main()
