"""MVP 마이그레이션 스크립트.

설정된 PG_URL DB에 mcp 내부 테이블 4종을 생성한다.
운영 마이그레이션은 추후 alembic 도입 검토

사용법:
    cd mcp-server
    uv run python scripts/create_tables.py [--drop]
"""

import argparse
import asyncio
import sys

from mcp_server.db.base import Base
from mcp_server.db.session import get_engine, reset_engine

# models 를 import 해야 Base.metadata 에 테이블이 등록됨
from mcp_server.db import models  # noqa: F401


async def create_tables(drop_first: bool) -> None:
    engine = get_engine()
    async with engine.begin() as conn:
        if drop_first:
            print("[!] 기존 테이블 DROP", file=sys.stderr)
            await conn.run_sync(Base.metadata.drop_all)
        print("[+] 테이블 생성", file=sys.stderr)
        await conn.run_sync(Base.metadata.create_all)
    await reset_engine()
    print("[OK] done", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description="MCP 내부 DB 테이블 생성")
    parser.add_argument(
        "--drop",
        action="store_true",
        help="실행 전 기존 테이블 DROP (개발용, 운영 금지)",
    )
    args = parser.parse_args()
    asyncio.run(create_tables(drop_first=args.drop))


if __name__ == "__main__":
    main()
