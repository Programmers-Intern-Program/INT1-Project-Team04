"""부동산 도메인 api_source row 등록

국토교통부 부동산 실거래가 API 메타데이터를 mcp 내부 DB 의 api_source 테이블에 시드한다.
- 아파트 매매: tool_name='search_house_price'
- 아파트 전월세: tool_name='search_house_rent'
멱등: 동일 tool_name row 가 이미 있으면 update, 없으면 insert.

서비스 키(serviceKey) 는 param_schema 에 포함하지 않는다.
도구 레이어 가 settings.molit_trade_api_key / settings.molit_rent_api_key 를
도구별로 로드해 호출 시점에 합성하는 책임.

사용법:
    cd mcp-server
    uv run python scripts/seed_real_estate_source.py
"""

import asyncio
import sys

from sqlalchemy import select

from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session, reset_engine

# 기본 param_schema — 매매/전월세 공통 (LAWD_CD + DEAL_YMD)
_BASE_PARAM_SCHEMA: dict = {
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

SOURCES: list[dict] = [
    {
        "tool_name": "search_house_price",
        "name": "국토교통부 아파트 매매 실거래가",
        "url_template": (
            "https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/"
            "getRTMSDataSvcAptTradeDev"
        ),
        "param_schema": _BASE_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_house_rent",
        "name": "국토교통부 아파트 전월세 실거래가",
        "url_template": "https://apis.data.go.kr/1613000/RTMSDataSvcAptRent",
        "param_schema": _BASE_PARAM_SCHEMA,
    },
]


async def _upsert_source(spec: dict) -> None:
    async with get_session() as session:
        existing = await session.execute(
            select(ApiSource).where(ApiSource.tool_name == spec["tool_name"])
        )
        row = existing.scalar_one_or_none()

        if row is None:
            row = ApiSource(**spec)
            session.add(row)
            print(f"[+] api_source insert: tool_name={spec['tool_name']}", file=sys.stderr)
        else:
            row.name = spec["name"]
            row.url_template = spec["url_template"]
            row.param_schema = spec["param_schema"]
            print(
                f"[~] api_source update: id={row.id}, tool_name={spec['tool_name']}",
                file=sys.stderr,
            )

        await session.commit()
        await session.refresh(row)
        print(f"[OK] api_source.id={row.id}, tool_name={spec['tool_name']}", file=sys.stderr)


async def seed() -> None:
    for spec in SOURCES:
        await _upsert_source(spec)
    await reset_engine()


def main() -> None:
    asyncio.run(seed())


if __name__ == "__main__":
    main()
