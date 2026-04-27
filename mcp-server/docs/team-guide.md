# MCP Server — Team Guide

부동산 / 법률 / 채용 / 경매 4개 도메인의 변화를 감시하는 Python MCP 서버. Spring Boot 백엔드(Spring AI MCP Client)가 이 서버를 호출해 도구(tool)를 실행한다.

이 문서는 **MCP 서버 작업에 처음 들어오는 팀원**이 30분 안에 로컬 기동 → 도구 호출까지 끝낼 수 있도록 작성된 가이드다.

---

## 1. 이 서버가 하는 일

- **역할**: 외부 데이터 소스(공공 API / 크롤링)에 접근하는 모든 호출을 한 곳에 모아 MCP 프로토콜로 노출.
- **호출자**: Spring Boot 백엔드의 Spring AI MCP Client (SSE 트랜스포트로 접속).
- **반환**: 모든 도구는 `{ text, structured, source_url, metadata }` 공통 스키마로 응답.
- **경계 원칙**: 메인 DB ↔ MCP 경계 분리. 백엔드는 **tool 이름 + 인자**만 보내고, MCP 서버는 가공된 결과 텍스트만 돌려준다. 백엔드는 내부에서 API를 썼는지 크롤링했는지 알지 못한다.

---

## 2. 시스템 안에서의 위치

```
[Next.js]
   │ REST / SSE
   ▼
[Spring Boot]  ── MCP Client (SSE) ──▶  [Python MCP Server] ── ▶  공공 API / 크롤링
                                              │
                                              └─ 내부 DB(mcp_dev): api_source / crawl_source / *_cache
```

- 메인 DB는 Spring Boot 가 소유. MCP 서버는 자체 DB(`mcp_dev`) 만 본다.
- MCP 서버 내부 캐시 / API 키 / 크롤링 셀렉터는 백엔드에 노출되지 않는다.

---

## 3. 사전 준비

### 3.1 필수 도구

| 도구 | 버전 | 비고 |
|---|---|---|
| Python | 3.11+ | |
| [uv](https://docs.astral.sh/uv/) | 0.5+ | 의존성 관리 (`pip` + `venv` 통합) |
| Docker | — | 로컬 Postgres 컨테이너 |

### 3.2 의존성 설치

```bash
cd mcp-server
uv sync
```

### 3.3 환경변수 (`.env`)

```bash
cp .env.example .env
```

채워야 할 핵심 값:

| 변수 | 용도 | 비고 |
|---|---|---|
| `PG_URL` | MCP 내부 DB 접속 (asyncpg) | 예: `postgresql+asyncpg://devuser:devpass@localhost:5432/mcp_dev` |
| `MCP_TRANSPORT` | `stdio` (Inspector 디버그) / `sse` (백엔드 연동) | 기본 `stdio` |
| `MCP_SSE_HOST` / `MCP_SSE_PORT` | SSE 바인딩 | 기본 `0.0.0.0:8090` |
| `MOLIT_TRADE_API_KEY` | 국토교통부 아파트 매매 실거래가 서비스 키 | data.go.kr 활용 신청 후 발급. 미설정 시 `search_house_price` 가 즉시 거부 |
| `LANGFUSE_*` | 트레이싱 (선택) | 단위 테스트는 `LANGFUSE_ENABLED=false` 권장 |

### 3.4 로컬 DB 준비 (한 번만)

```bash
# 1) Postgres 컨테이너가 떠 있다고 가정. 없으면 인프라 담당자에게 문의.
docker start local-db    # 또는 팀에서 사용하는 컨테이너 이름

# 2) MCP 전용 DB / 롤 생성 (이미 있으면 already exists 무시)
docker exec local-db psql -U <postgres-superuser> -d postgres -c \
  "CREATE ROLE devuser WITH LOGIN PASSWORD 'devpass';"
docker exec local-db psql -U <postgres-superuser> -d postgres -c \
  "CREATE DATABASE mcp_dev OWNER devuser;"

# 3) 스키마 생성 (api_source / crawl_source / api_cache / crawl_cache 4개 테이블)
uv run python scripts/create_tables.py

# 4) 도메인별 시드 (멱등)
uv run python scripts/seed_real_estate_source.py
```

검증:
```bash
docker exec local-db psql -U devuser -d mcp_dev -c \
  "SELECT tool_name, name FROM api_source;"
# → search_house_price | 국토교통부 아파트 매매 실거래가
```

---

## 4. 서버 기동

### 4.1 stdio 모드 (로컬 디버그)

MCP Inspector 로 도구를 직접 테스트할 때.

```bash
# .env 에서 MCP_TRANSPORT=stdio
npx @modelcontextprotocol/inspector uv run mcp-server
```

### 4.2 SSE 모드 (백엔드 연동용)

```bash
# .env 에서 MCP_TRANSPORT=sse
uv run python -m mcp_server
# 또는
uv run mcp-server
```

> **주의**: `python -m mcp_server.server` 형태는 사용하지 말 것. server.py 가 `__main__` 으로 로드되어 도구 모듈이 참조하는 mcp 인스턴스와 분리되는 함정 때문에 의도적으로 차단되어 있다. 표준 명령은 위 두 가지다.

검증:
```bash
curl http://localhost:8090/health
# → {"status":"ok"}
```

---

## 5. 제공 도구

### `search_house_price`

국토교통부 아파트 매매 실거래가 조회.

**입력**

| 필드 | 타입 | 설명 |
|---|---|---|
| `region` | string | 시군구명 또는 5자리 법정동코드 (예: `"강남구"`, `"서울 중구"`, `"11680"`) |
| `deal_ymd` | string (`YYYYMM`) | 거래 연월 (예: `"202403"`) |

**반환** — 공통 스키마

```json
{
  "text": "LAWD_CD 11680 202403 아파트 매매 실거래 100건. 평균 21.6억 ...",
  "structured": {
    "summary": { "count": 100, "avg_deal_amount": 216170, "min_deal_amount": 17000, "max_deal_amount": 750000 },
    "trades": [ { "apt_name": "...", "deal_amount": 750000, "deal_date": "2024-03-12", ... } ],
    "trades_truncated": true,
    "query": { "region": "강남구", "lawd_cd": "11680", "deal_ymd": "202403" }
  },
  "source_url": "https://apis.data.go.kr/.../getRTMSDataSvcAptTradeDev?...",
  "metadata": { "fetched_at": "...", "raw_count": 100, "returned_count": 20, "tool_name": "search_house_price", "source_id": 1 }
}
```

> **단위 규약**: `structured` 의 가격은 모두 **만원 단위 정수**. `text` 는 사람 가독성을 위해 "억" 으로 환산.

**SSE 클라이언트로 호출 예시**

```python
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession

async def main():
    async with sse_client("http://localhost:8090/sse") as (r, w):
        async with ClientSession(r, w) as s:
            await s.initialize()
            res = await s.call_tool(
                "search_house_price",
                {"input": {"region": "강남구", "deal_ymd": "202403"}},
            )
            print(res.structuredContent)

asyncio.run(main())
```

---

## 6. 새 도구 추가 가이드

도구 1개 추가 = 한 파일 + import 1줄 + 테스트.

### 6.1 도구 파일 작성

`src/mcp_server/tools/<domain>.py`:

```python
from mcp_server.server import mcp
from mcp_server.observability.tracing import traced
from pydantic import BaseModel, Field


class SomeToolInput(BaseModel):
    keyword: str = Field(description="검색어")


@mcp.tool()                  # ← 등록
@traced("some_tool")         # ← Langfuse 트레이싱 (필수)
async def some_tool(input: SomeToolInput) -> dict:
    """도구 설명 — AI 가 읽는 인터페이스이므로 명확하게."""
    # 외부 호출은 반드시 sources 레이어 경유 (아래 7번 항목)
    return {
        "text": "...",
        "structured": {...},
        "source_url": "...",
        "metadata": {...},
    }
```

### 6.2 자동 등록 트리거에 한 줄 추가

`src/mcp_server/tools/__init__.py`:

```python
from mcp_server.tools import real_estate  # 기존
from mcp_server.tools import <domain>     # ← 추가
```

이 한 줄이 빠지면 도구가 등록되지 않는다.

### 6.3 단위 테스트

`tests/tools/test_<domain>.py` 에 호출 → 반환 스키마 검증 추가. 외부 API 는 `httpx.MockTransport` 또는 픽스처 XML 로 모킹.

### 6.4 등록 검증

```bash
uv run pytest tests/test_server.py::test_registered_tools_include_search_house_price -v
# 새 도구는 같은 패턴으로 테스트 추가 (set 에 이름 한 줄)
```

---

## 7. 외부 호출 규약

도구 안에서 `httpx` / `playwright` 를 직접 부르지 말 것. 항상 서비스 레이어 경유:

```python
from mcp_server.sources.api_source_service import fetch_by_tool_name

raw = await fetch_by_tool_name("search_house_price", params={"LAWD_CD": "11680", "DEAL_YMD": "202403"})
```

이유:
- 캐시(향후) / 트레이싱 / 에러 분류가 한 곳에 모임
- API 키 같은 비밀값을 도구 코드 안에 박지 않음
- 데이터 소스 메타(`api_source` / `crawl_source` 테이블) 에서 URL 템플릿이 관리됨 → 엔드포인트 변경 시 코드 변경 없이 DB 만 업데이트

같은 원칙이 크롤링에는 `crawl_source_service` 로 적용된다.

---

## 8. 자주 겪는 문제

| 증상 | 원인 / 해결 |
|---|---|
| `RealEstateConfigError: MOLIT_TRADE_API_KEY 미설정` | `.env` 에 키 누락. data.go.kr 활용 신청 후 채울 것. |
| SSE 클라이언트가 `list_tools` 에서 빈 배열 받음 | `python -m mcp_server.server` 로 띄웠을 가능성. 표준 명령 `python -m mcp_server` 또는 `uv run mcp-server` 사용. |
| `relation "api_source" does not exist` | 3.4 절의 `create_tables.py` 미실행. |
| `tool_name=... 에 해당하는 api_source row 가 없음` | 시드 누락. 해당 도메인 `seed_*.py` 실행. |
| XML resultCode != "000" | 공공 API 활용 신청이 미승인이거나 일일 호출 한도 초과. data.go.kr 마이페이지 확인. |
| `password authentication failed for user "devuser"` | 3.4 절의 롤 비밀번호와 `.env` 의 `PG_URL` 비밀번호가 다름. `ALTER ROLE devuser WITH PASSWORD 'devpass';` 로 동기화. |
| `pytest` 가 0개 테스트 발견 | `mcp-server/` 디렉터리에서 실행했는지 확인. |
| `playwright` import 에러 | `uv run playwright install chromium` 한 번 실행. |

---

## 9. 디렉터리 구조 (요약)

```
mcp-server/
  src/mcp_server/
    __main__.py         # python -m mcp_server 표준 엔트리
    server.py           # FastMCP 인스턴스 + main()
    config.py           # pydantic-settings
    tools/              # @mcp.tool() 등록 — 도메인별 1파일
    sources/            # 외부 호출 서비스 레이어
    domains/            # 도메인별 응답 정규화
    db/                 # SQLAlchemy 모델 / 세션
    observability/      # @traced (Langfuse 래퍼)
  scripts/
    create_tables.py    # MCP 내부 DB 스키마 생성
    seed_*.py           # 도메인별 api_source/crawl_source 시드
  tests/                # pytest
  data/                 # 정적 매핑 데이터 (예: lawd_codes.json)
  pyproject.toml
  .env.example
```

---

## 10. 추가 정보

- **공통 반환 스키마 / 트레이싱 규약 / async session 규약**: 본 문서 5·6·7장 + 코드 주석 참조.
- **운영 배포 (Dockerfile / CI / docker-compose)**: 본 문서는 로컬 개발 범위까지 다룬다.
