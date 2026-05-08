"""경매 도메인 api_source row 등록.

조달청 나라장터 입찰공고정보서비스 1종을 mcp 내부 DB 의 api_source 테이블에 시드.
멱등: tool_name 기준 upsert.

사용법:
    cd mcp-server
    uv run python scripts/seed_auction_source.py
"""

import asyncio
import sys

from sqlalchemy import select

from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session, reset_engine

_G2B_BID_PARAM_SCHEMA: dict = {
    "type": "object",
    "required": ["serviceKey", "inqryBgnDt", "inqryEndDt"],
    "properties": {
        "serviceKey": {"type": "string"},
        "pageNo": {"type": "integer", "minimum": 1, "default": 1},
        "numOfRows": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
        "type": {"type": "string", "enum": ["json", "xml"], "default": "json"},
        "inqryDiv": {"type": "integer", "enum": [1, 2], "default": 1},
        "inqryBgnDt": {"type": "string", "pattern": "^[0-9]{12}$"},
        "inqryEndDt": {"type": "string", "pattern": "^[0-9]{12}$"},
    },
}


SOURCES: list[dict] = [
    {
        "tool_name": "search_g2b_bid",
        "name": "조달청 나라장터 입찰공고정보서비스",
        # data.go.kr/data/15129394. 1230000 = 조달청 기관코드.
        # 입찰공고 목록 조회 오퍼레이션. 발급 사양에 따라 BidPublicInfoServc01/02/04
        # 등 변형 있음. 호출 실패 시 url_template 만 교체.
        "url_template": (
            "https://apis.data.go.kr/1230000/ad/BidPublicInfoService/getBidPblancListInfoServc"
        ),
        "param_schema": _G2B_BID_PARAM_SCHEMA,
    },
]


async def _upsert_source(spec: dict) -> None:
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
    print(f"[OK] seeded {len(SOURCES)} auction api_source row(s)", file=sys.stderr)
    await reset_engine()


def main() -> None:
    asyncio.run(seed())


if __name__ == "__main__":
    main()
