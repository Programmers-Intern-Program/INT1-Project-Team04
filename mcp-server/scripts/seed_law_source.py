"""법률 도메인 api_source row 등록.

법제처 국가법령정보 + 국회 의안정보 2종을 mcp 내부 DB 의 api_source 테이블에 시드.
멱등: tool_name 기준으로 존재하면 update, 없으면 insert.

서비스 키는 param_schema 에 포함하지 않는다 — 도구 레이어가 settings 로 합성.

url_template 주의:
  실제 발급기관마다 엔드포인트가 다를 수 있다. 호출 실패(SourceFetchError) 시
  이 파일의 url_template 만 수정하면 된다 (도구 코드 변경 불필요).

사용법:
    cd mcp-server
    uv run python scripts/seed_law_source.py
"""

import asyncio
import sys

from sqlalchemy import select

from mcp_server.db.models import ApiSource
from mcp_server.db.session import get_session, reset_engine

# 법제처 명세 기준: 인증키/서비스대상/검색어/페이지수/페이지번호 5개 모두 필수.
# 응답은 XML 고정 (JSON 미지원).
_LAW_INFO_PARAM_SCHEMA: dict = {
    "type": "object",
    "required": ["serviceKey", "target", "query", "numOfRows", "pageNo"],
    "properties": {
        "serviceKey": {"type": "string"},
        "target": {"type": "string", "enum": ["law"]},
        "query": {"type": "string", "description": "검색어 (default: *)"},
        "numOfRows": {"type": "integer", "minimum": 1, "maximum": 9999, "default": 10},
        "pageNo": {"type": "integer", "minimum": 1, "default": 1},
    },
}

_BILL_INFO_PARAM_SCHEMA: dict = {
    "type": "object",
    "required": ["KEY", "AGE"],
    "properties": {
        "KEY": {"type": "string"},
        "AGE": {"type": "integer", "minimum": 1, "maximum": 22},
        "Type": {"type": "string", "enum": ["xml", "json"], "default": "xml"},
        "pIndex": {"type": "integer", "minimum": 1, "default": 1},
        "pSize": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
    },
}


SOURCES: list[dict] = [
    {
        "tool_name": "search_law_info",
        "name": "법제처 국가법령정보 공유서비스 (현행법령 목록조회)",
        # data.go.kr/data/15000115. 명세 요청주소: lawSearchList.do (XML 응답).
        # 본 시드는 현행법령(target=law) 만 등록. 자치법규/해석례/헌재결정례/별표서식
        # /법령용어/조약 등 6종은 url_template 만 다른 동일 패턴이므로 추후 도구 추가
        # 시 별도 시드 row 로 등록.
        "url_template": "http://apis.data.go.kr/1170000/law/lawSearchList.do",
        "param_schema": _LAW_INFO_PARAM_SCHEMA,
    },
    {
        "tool_name": "search_bill_info",
        "name": "국회사무처 의안정보 통합 API (열린국회정보)",
        # data.go.kr/data/15126134 의 키는 실제로 open.assembly.go.kr (열린국회정보) 인증키.
        # nzmimeepazxkubdpn = 의안정보 발의일자 기준 목록 식별자.
        "url_template": "https://open.assembly.go.kr/portal/openapi/nzmimeepazxkubdpn",
        "param_schema": _BILL_INFO_PARAM_SCHEMA,
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
    print(f"[OK] seeded {len(SOURCES)} law api_source row(s)", file=sys.stderr)
    await reset_engine()


def main() -> None:
    asyncio.run(seed())


if __name__ == "__main__":
    main()
