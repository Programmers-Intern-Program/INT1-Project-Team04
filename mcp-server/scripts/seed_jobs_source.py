"""채용 도메인 api_source row 등록.

기재부 공공기관 + 워크넷 2종을 mcp 내부 DB 의 api_source 테이블에 시드.
멱등: tool_name 기준 upsert.

서비스 키는 param_schema 에 포함하지 않는다 — 도구 레이어가 settings 로 합성.

사용법:
    cd mcp-server
    uv run python scripts/seed_jobs_source.py
"""

import asyncio
import sys

from sqlalchemy import select

from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session, reset_engine

_PUBLIC_JOB_PARAM_SCHEMA: dict = {
    "type": "object",
    "required": ["serviceKey"],
    "properties": {
        "serviceKey": {"type": "string"},
        "pageNo": {"type": "integer", "minimum": 1, "default": 1},
        "numOfRows": {"type": "integer", "minimum": 1, "maximum": 100, "default": 10},
        "resultType": {"type": "string", "enum": ["json", "xml"], "default": "json"},
        "ongoingYn": {"type": "string", "enum": ["Y", "N"]},
        "recrutPbancTtl": {"type": "string", "description": "공시제목 부분일치"},
        "pbancBgngYmd": {"type": "string", "pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
        "pbancEndYmd": {"type": "string", "pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
        "pblntInstCd": {"type": "string"},
        "instClsf": {"type": "string"},
        "instType": {"type": "string"},
        "recrutSe": {"type": "string"},
        "replmprYn": {"type": "string", "enum": ["Y", "N"]},
        "workRgnLst": {"type": "string"},
        "ncsCdLst": {"type": "string"},
        "hireTypeLst": {"type": "string"},
        "acbgCondLst": {"type": "string"},
    },
}

_WORKNET_PARAM_SCHEMA: dict = {
    "type": "object",
    "required": ["authKey", "callTp", "returnType"],
    "properties": {
        "authKey": {"type": "string"},
        "callTp": {"type": "string", "enum": ["L", "D"]},
        "returnType": {"type": "string", "enum": ["XML", "JSON"]},
        "startPage": {"type": "integer", "minimum": 1, "default": 1},
        "display": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
        "keyword": {"type": "string"},
    },
}


SOURCES: list[dict] = [
    {
        "tool_name": "search_public_job",
        "name": "기획재정부 공공기관 채용정보 조회서비스 (목록조회)",
        # data.go.kr/data/15125273. base=apis.data.go.kr/1051000/recruitment + 오퍼레이션 /list.
        # 채용공시 상세조회는 /detail (별도 도구로 분리하려면 추가 시드).
        "url_template": "https://apis.data.go.kr/1051000/recruitment/list",
        "param_schema": _PUBLIC_JOB_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_worknet_job",
        "name": "한국고용정보원 워크넷 채용정보",
        # data.go.kr/data/3038225. 워크넷 → work24 이전. legacy 도메인 (openapi.work.go.kr)
        # 도 동작하지만 work24 가 공식.
        "url_template": "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo210L01.do",
        "param_schema": _WORKNET_PARAM_SCHEMA,
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
    print(f"[OK] seeded {len(SOURCES)} jobs api_source row(s)", file=sys.stderr)
    await reset_engine()


def main() -> None:
    asyncio.run(seed())


if __name__ == "__main__":
    main()
