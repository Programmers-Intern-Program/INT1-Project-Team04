# MCP Server 사용 가이드

부동산 / 법률 / 채용 / 경매 4개 도메인의 변화를 감시하는 Python MCP 서버. Spring Boot 백엔드(Spring AI MCP Client)가 SSE 트랜스포트로 이 서버를 호출해 도구(tool)를 실행한다.

---

## 어디부터 읽을지

| 당신의 목적 | 먼저 읽을 섹션           |
|---|--------------------|
| 처음 합류 — 30분 안에 로컬 기동까지 | §1 → §2 → §3 → §4  |
| 백엔드(Spring AI)에서 이 서버를 호출하고 싶다 | §1 → §5            |
| 도구를 직접 한 번 호출해 보고 싶다 (Inspector / Python SSE) | §4.3 → §6          |
| **MCP 서버에 어떤 작업이든 새로 추가하고 싶다** (스냅샷·알림·새 도메인·새 도구 등) | §7 (반드시 §7.1 결정 기준부터) |
| 외부 API/SDK 호출이 필요한 작업이라 서비스 레이어 패턴이 궁금하다 | §8 |
| 응답이 이상하다 / 통합 시 결과 처리 헷갈림 | §6 (스키마) + §9 (에러) |
| 코드에서 `crawl_*` / `playwright` 흔적을 봤다 | §14 (현재 미구현, 활성화 절차) |
| 무언가 안 된다 | §11 (트러블슈팅) |

---

## 1. 이 서버가 하는 일

- **역할**: 외부 데이터 소스(공공 API)에 접근하는 모든 호출을 한 곳에 모아 MCP 프로토콜로 노출.
- **호출자**: Spring Boot 백엔드의 Spring AI MCP Client. SSE 트랜스포트로 접속(§5).
- **반환**: 모든 도구는 `{ text, structured, source_url, metadata }` **공통 스키마**(§6)로 응답.
- **경계 원칙** (CLAUDE.md "절대 지킬 것" 1번): 메인 DB ↔ MCP 경계 분리.
  - 백엔드는 **tool 이름 + 인자**만 보낸다.
  - MCP 서버는 가공된 결과 텍스트만 돌려준다.
  - 백엔드는 내부에서 어떤 자료원을 썼는지 알지 못한다 — **수집 방식이 바뀌어도 백엔드 영향 없어야 한다**.

> **현재 범위**: 공공 API(data.go.kr 계열)만 활성. 크롤링 경로는 골격 코드와 DB 스키마는 존재하나 미구현. 자세한 활성화 절차는 §14 참조.

---

## 2. 시스템 안에서의 위치

```
[Next.js]
   │ REST / SSE
   ▼
[Spring Boot]  ── MCP Client (SSE) ──▶  [Python MCP Server] ── ▶  공공 API
                                              │
                                              └─ 내부 DB(mcp_dev): api_source / api_cache
```

- **메인 DB**(Spring Boot 소유): `users`, `subscription`, `schedule`, `notification`, `ai_data_hub` 등
- **MCP 서버 DB**(`mcp_dev`, MCP 서버 전용): `api_source`, `api_cache` (그리고 `crawl_source`/`crawl_cache` DDL은 존재하나 현재 미사용 — §14)
- API 키 / 캐시 정책은 **MCP 서버 안에서만** 관리되고 백엔드에는 노출되지 않는다.

---

## 3. 사전 준비

### 3.1 필수 도구

| 도구 | 버전 | 비고 |
|---|---|---|
| Python | 3.11+ | `pyproject.toml:requires-python = ">=3.11"` |
| [uv](https://docs.astral.sh/uv/) | 0.5+ | 의존성 관리 (`pip` + `venv` 통합) |
| Docker | — | 로컬 Postgres 컨테이너 |
| Node | (선택) | MCP Inspector 실행용 |

### 3.2 의존성 설치

```bash
cd mcp-server
uv sync
```

`uv sync`가 `.venv/`를 만들고 `pyproject.toml`의 모든 의존성을 설치한다. 이후 모든 명령은 `uv run ...` 또는 `.venv` 활성화 후 실행.

### 3.3 환경변수 (`.env`)

```bash
cp .env.example .env
```

채워야 할 핵심 값(전체 키 설명은 `.env.example` 주석 참조):

| 변수 | 용도 | 비고 |
|---|---|---|
| `PG_URL` | MCP 내부 DB 접속 (asyncpg) | 예: `postgresql+asyncpg://devuser:devpass@localhost:5432/mcp_dev` |
| `MCP_TRANSPORT` | `stdio` (Inspector 디버그) / `sse` (백엔드 연동) | 기본 `stdio` |
| `MCP_SSE_HOST` / `MCP_SSE_PORT` | SSE 바인딩 | 기본 `0.0.0.0:8090` |
| `MOLIT_TRADE_API_KEY` | 국토교통부 아파트 매매 실거래가 서비스 키 | data.go.kr 활용 신청 후 발급. 미설정 시 `search_house_price` 즉시 거부 |
| `LANGFUSE_ENABLED` | 트레이싱 on/off | 단위 테스트는 `false` 권장 |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_HOST` | Langfuse 활성 시 필수 | 비활성이면 빈 값 가능 |

> **새 환경변수 추가 시 양쪽 갱신**: `src/mcp_server/config.py`의 `Settings` 필드와 `.env.example` 양쪽을 함께 수정한다. `.env.example`의 키와 `Settings` 필드는 1:1 매핑.

### 3.4 로컬 DB 준비 (한 번만)

```bash
# 1) Postgres 컨테이너 기동 (이미 떠 있다면 생략)
docker start local-db    # 팀에서 사용하는 컨테이너 이름

# 2) MCP 전용 DB / 롤 생성 (이미 있으면 already exists 무시)
docker exec local-db psql -U <postgres-superuser> -d postgres -c \
  "CREATE ROLE devuser WITH LOGIN PASSWORD 'devpass';"
docker exec local-db psql -U <postgres-superuser> -d postgres -c \
  "CREATE DATABASE mcp_dev OWNER devuser;"

# 3) 스키마 생성 (현재 활성: api_source / api_cache. crawl_* 는 DDL만 생성 — §14)
uv run python scripts/create_tables.py

# 4) 도메인별 시드 (멱등 — 두 번 실행해도 안전)
uv run python scripts/seed_real_estate_source.py
```

검증:
```bash
docker exec local-db psql -U devuser -d mcp_dev -c \
  "SELECT tool_name, name FROM api_source;"
# → search_house_price | 국토교통부 아파트 매매 실거래가
```

---

## 4. 서버 기동 & 첫 호출

### 4.1 stdio 모드 (로컬 디버그)

MCP Inspector로 도구를 직접 테스트할 때.

```bash
# .env 에서 MCP_TRANSPORT=stdio
npx @modelcontextprotocol/inspector uv run mcp-server
```

브라우저에 Inspector UI가 뜨면 → **Tools** 탭 → `search_house_price` 선택 → 인자 `{"region":"강남구","deal_ymd":"202403"}` 입력 → Run.

### 4.2 SSE 모드 (백엔드 연동용)

```bash
# .env 에서 MCP_TRANSPORT=sse
uv run python -m mcp_server
# 또는 동일
uv run mcp-server
```

⚠️ **주의** (자주 빠지는 함정 — `src/mcp_server/__main__.py:1-9` 주석 참조):
**`python -m mcp_server.server` 형태는 사용하지 말 것.**
이렇게 실행하면 `server.py`가 `__main__`으로 로드되어 도구 모듈이 참조하는 `mcp` 인스턴스와 **별개 객체**가 생긴다 → SSE 클라이언트의 `list_tools`가 빈 배열을 받는다.
표준 명령은 위 두 가지(`python -m mcp_server` / `uv run mcp-server`)뿐.

검증:
```bash
curl http://localhost:8090/health
# → {"status":"ok"}
```

### 4.3 첫 도구 호출 (Python SSE 클라이언트)

```python
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession

async def main():
    async with sse_client("http://localhost:8090/sse") as (r, w):
        async with ClientSession(r, w) as s:
            await s.initialize()

            tools = await s.list_tools()
            print("등록 도구:", [t.name for t in tools.tools])

            res = await s.call_tool(
                "search_house_price",
                {"input": {"region": "강남구", "deal_ymd": "202403"}},
            )
            print(res.structuredContent)

asyncio.run(main())
```

---

## 5. Spring AI MCP Client 통합 (백엔드 팀 관점)

### 5.1 의존성 (`back/build.gradle.kts`)

```gradle
implementation("org.springframework.ai:spring-ai-starter-mcp-client")
```

### 5.2 application.yml 설정

`back/src/main/resources/application.yml:28-33`:
```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            python-mcp:
              url: ${MCP_SERVER_URL:http://localhost:8090}
```

- 연결 이름(`python-mcp`)은 임의. SSE URL만 정확히 맞으면 됨.
- 운영에서는 `MCP_SERVER_URL` 환경변수로 주입.

### 5.3 ChatClient에 도구 콜백 주입

`back/.../adapter/out/ai/AiConfig.java`:
```java
@Bean
public ChatClient monitorChatClient(ChatClient.Builder builder,
                                    Optional<ToolCallbackProvider> toolCallbackProvider) {
    toolCallbackProvider.ifPresent(builder::defaultToolCallbacks);
    return builder.build();
}
```

`spring-ai-starter-mcp-client`가 `ToolCallbackProvider`를 자동 등록 → `ChatClient`가 LLM의 tool-calling 시 MCP 도구를 자동 선택·호출.

### 5.4 MCP 도구 직접 호출 (Spring 측)

LLM 우회해 도구를 명시적으로 호출하고 싶을 때 — `back/.../adapter/out/persistence/mcp/McpHttpAdapter.java`:
```java
McpSchema.CallToolResult result = clientFor(tool.name()).callTool(
    new McpSchema.CallToolRequest(tool.name(), arguments)
);
```

### 5.5 응답 처리 시 주의

도구 응답은 §6의 공통 스키마. **`text`만 LLM에 다시 넣고**, `structured`는 백엔드에서 후처리(임베딩·차이 비교·알림 메시지 빌드)에 활용. 백엔드가 자유 텍스트를 정규식으로 파싱하지 말 것 (CLAUDE.md anti-pattern).

---

## 6. 응답 공통 스키마

모든 도구는 다음 dict를 반환한다.

```json
{
  "text": "사람이 읽는 한 단락 요약 (LLM 컨텍스트로 그대로 전달 가능)",
  "structured": {
    "summary": { "...": "도메인별 통계 (예: count, avg/min/max)" },
    "trades":  [ { "...": "도메인별 record dict" } ],
    "trades_truncated": false,
    "query":   { "...": "호출 인자 echo" }
  },
  "source_url": "https://... (원본 호출 URL, serviceKey 는 *** 마스킹)",
  "metadata":   {
    "fetched_at": "ISO8601",
    "raw_count": 100,
    "returned_count": 20,
    "tool_name": "search_house_price",
    "source_id": 1
  }
}
```

### 왜 `text`와 `structured`를 분리하는가

- `text`: **LLM 컨텍스트 / 사용자 알림 본문**용. 토큰 효율을 위해 통계 한 단락으로 압축.
- `structured`: **프로그램이 처리하는 데이터**(임베딩, 변화 감지, 차트 렌더링)용. 손실 없는 정형 dict.
- 한 문자열 안에 두 정보가 섞이면 백엔드가 자유 텍스트를 정규식 파싱해야 함 → 할루시네이션 / 파싱 실패 위험. CLAUDE.md "할루시네이션 방어는 구조로" 원칙(anti-pattern).

### 단위 규약

- `structured`의 모든 가격 필드는 **만원 단위 정수**.
- `text`는 사람 가독성을 위해 "억" 환산 (예: `"평균 21.6억"`).
- 환산은 `tools/real_estate.py:_to_eok()` 같은 빌더 함수가 책임.

### 절단(truncate) 규약

- `structured.trades`는 토큰 폭증 방지를 위해 상위 N건(real_estate는 20건)으로 절단.
- 절단되면 `trades_truncated=true`. 원본 건수는 `summary.count` / `metadata.raw_count`로 보존.
- 정렬 기준: 도메인이 정함 (real_estate 매매는 가격 내림차순).

### 도구 카탈로그

#### `search_house_price` — 아파트 매매 실거래가 조회 (real_estate)

| 입력 필드 | 타입 | 설명 |
|---|---|---|
| `region` | string | 시군구명 또는 5자리 법정동코드 (예: `"강남구"`, `"서울 중구"`, `"11680"`). 동·읍·면 단위 불가. 동음이의(`"중구"`)는 시도와 함께 입력. |
| `deal_ymd` | string `YYYYMM` | 거래 연월 (예: `"202403"`) |

자세한 반환 예시는 §6 상단의 공통 스키마 참조. 자세한 정렬·통계 규약은 `tools/real_estate.py:search_house_price` docstring.

(추후 도구가 추가되면 이 표에 한 줄씩 추가.)

---

## 7. MCP 서버에 새 작업 추가하기

내 작업이 어떤 영역(스냅샷·알림·새 도메인·새 도구 무엇이든)이든, 이 섹션 하나만 따라 하면 된다. **영역별 추측은 본 가이드에 박지 않고**, "어디에 무엇을 어떻게 두는지"만 일반화해 안내한다. 영역별 세부 결정은 각 담당자가 ADR(§7.7)에 기록한다.

### 7.1 0단계 — 내 작업이 MCP 서버에 들어갈 일인가?

먼저 결정. 잘못된 위치에 시간을 쓰지 않게.

| 들어가는 게 맞다 | 들어가지 않는 게 맞다 |
|---|---|
| 외부 데이터 소스 호출 (API / 크롤링 / 검색) | **시간 트리거**(Cron / `@Scheduled`) — 백엔드(Spring) 책임. MCP 서버는 호출당하는 쪽. |
| 백엔드/LLM이 **MCP 도구로 호출**해야 할 기능 | 사용자/구독/권한 관리 — 메인 DB(`subscription`/`users`) 소유 |
| MCP 서버만 알아야 할 데이터 (캐시, 외부 호출 메타) | 발송 멱등키·재시도 정책이 메인 DB와 묶이는 기능 (보통 백엔드 어댑터가 더 적합) |
| 도구 응답을 가공·정규화하는 도메인 로직 | 프론트가 직접 호출하는 REST API |

확신이 안 서면 **ADR을 먼저 한 장 쓰는 것**이 가장 빠른 길 (§7.7).

### 7.2 어디에 무엇을 추가하는가 (디렉토리·파일 매핑)

작업 종류와 무관하게, 추가할 것 ↔ 둘 위치는 다음 표 하나로 결정된다.

| 추가하려는 것 | 어디에 둘까 | 어떻게 |
|---|---|---|
| **MCP 도구** (LLM/백엔드가 호출) | `src/mcp_server/tools/<영역>.py` | 함수에 `@mcp.tool()` + `@traced("이름")` 두 데코레이터. 도구 이름은 동사+명사. |
| **도구 입력 스키마** | 같은 파일 안 `pydantic.BaseModel` 클래스 | 모든 필드에 `description`. **비밀값(`*_api_key`, `serviceKey` 등) 절대 포함 금지** — Langfuse `@traced`가 입력을 자동 캡처하므로 평문 노출. |
| **도구 응답** | 함수 반환 dict | §6 공통 스키마(`text`/`structured`/`source_url`/`metadata`) 준수. |
| **새 DB 테이블** | `src/mcp_server/db/models.py` | `class Foo(Base)`. JSONB 필요하면 기존 `JsonColumn` 패턴 재사용. `scripts/create_tables.py`가 자동으로 함께 생성한다(`Base.metadata.create_all()`). |
| **DB 시드 데이터** (정적 메타) | `scripts/seed_<영역>_source.py` 새 파일 | 멱등 upsert. 기존 `seed_real_estate_source.py`가 참고용. |
| **도메인 로직** (정규화/판정/임베딩 등) | `src/mcp_server/domains/<영역>/` 새 디렉토리 | `__init__.py`, `errors.py`, 기능별 모듈(예: `normalizer.py`, `embedding.py`, `similarity.py` …). |
| **도메인 전용 에러** | `src/mcp_server/domains/<영역>/errors.py` | `<영역>Error` 베이스 + 세부 에러(`ConfigError`, `NormalizationError`, …). **`sources/errors.py`의 `SourceError`와 분리할 것** — 외부 호출 실패와 도메인 의미 실패는 다른 종류 (§9). |
| **외부 호출** (HTTP/SDK) | `src/mcp_server/sources/<영역>_service.py` 또는 도메인 내부 `service.py` 새 파일 | **도구 함수 안에서 `httpx`/외부 SDK 직접 import 금지.** 서비스 레이어로 분리. 이유는 §8. |
| **환경변수 / 시크릿** | `src/mcp_server/config.py` `Settings` 필드 + `.env.example` 양쪽 1:1 동기화 | 기본값 `""` 유지(미발급 상태에서도 import/테스트 통과). 빈 값 검증은 도구 레이어 책임. |
| **트레이싱** | 자동 — `@traced("도구명")`만 붙이면 됨 | Langfuse 비활성 시 no-op. 직접 호출 코드 작성 불필요. |
| **단위 테스트** | `tests/<같은 위치>/test_<모듈>.py` | `pytest-asyncio`(자동 모드), `monkeypatch`로 외부 호출 격리, in-memory DB는 `patched_session_factory` 픽스처가 자동 제공. |
| **테스트 픽스처** (외부 응답 등) | `tests/fixtures/<영역>_<시나리오>.<확장자>` | 예: `tests/fixtures/molit_apt_trade_sample.xml` |
| **참고 사례** | `src/mcp_server/tools/real_estate.py` + `domains/real_estate/` + `scripts/seed_real_estate_source.py` + `tests/tools/test_real_estate.py` | "외부 데이터 조회 도구" 패턴의 레퍼런스 구현. 다른 종류의 작업에도 데코레이터·테스트·에러 분리 패턴은 그대로 적용 가능. |

### 7.3 어느 작업이든 지켜야 할 공통 규약 6개

영역과 무관하게 모든 새 작업이 따라야 하는 규약. 이걸 어기면 다른 팀원의 작업과 충돌하거나, 운영에서 보안/관측 사고로 이어진다.

1. **`@mcp.tool()` + `@traced("이름")` 두 데코레이터 모두**. 트레이싱은 옵션이 아님 (CLAUDE.md "Langfuse 연동 시점: 처음부터 붙일 것").
2. **외부 호출은 sources/ 또는 도메인 service 레이어로 분리**. 도구 함수 안에서 `httpx`/외부 SDK 직접 import 금지. 이유는 §8.
3. **비밀값을 도구 입력 스키마에 넣지 말 것.** `@traced`가 입력을 자동 캡처해 Langfuse에 평문이 남는다. 도구 내부에서 `settings.xxx`를 합성.
4. **도구 응답은 §6 공통 스키마**. `text`(LLM/사용자용 한 단락) + `structured`(프로그램용 정형 dict) 분리. 한 문자열 안에 두 정보 섞으면 백엔드가 자유 텍스트 정규식 파싱하다 깨진다.
5. **도구 이름은 동사+명사** (`search_*`, `save_*`, `send_*`, `get_*`, `register_*`). 모호한 `process_*`, `handle_*`, `do_*` 금지 (CLAUDE.md "Tool 이름과 Description이 AI의 판단 근거" 원칙).
6. **새 환경변수는 `Settings`와 `.env.example` 양쪽에 동시 추가**. 한 쪽만 추가하면 다음 사람이 셋업할 때 막힌다.

### 7.4 단계별 체크리스트 (영역 무관)

위에서 아래로 순서대로. 각 단계는 빌드/테스트가 그린 상태를 유지하도록 짜였다.

- [ ] **(0) ADR 작성** — `/docs/adr/NNNN-<주제>.md`. 이 작업이 MCP 서버에 들어가는 이유, 결정 사항, 영향. 코드 시작 전 (§7.7).
- [ ] **(1) 환경변수 필요하면 추가** — `Settings` + `.env.example` 동시 갱신.
- [ ] **(2) DB 테이블 필요하면 추가** — `db/models.py`에 클래스. `scripts/create_tables.py`는 그대로 작동.
- [ ] **(3) 도메인 로직 필요하면 추가** — `domains/<영역>/` 새 디렉토리 + `errors.py` + 기능 모듈.
- [ ] **(4) 외부 호출 필요하면 서비스 레이어 추가** — `sources/<영역>_service.py` 또는 도메인 내부 `service.py`. `httpx`/SDK는 여기서만 import.
- [ ] **(5) 도구 함수 작성** — `tools/<영역>.py`에 `@mcp.tool()` + `@traced("이름")`. 입력 스키마 BaseModel + 응답은 §6 공통 스키마.
- [ ] **(6) 도구 등록 트리거 추가** — `tools/__init__.py`에 `from mcp_server.tools import <new_module>` 한 줄 (§7.5).
- [ ] **(7) 시드 필요하면 추가** — `scripts/seed_<영역>_source.py` 멱등 스크립트.
- [ ] **(8) export_tool_schema 등록** — `scripts/export_tool_schema.py`의 `_TOOL_INPUT_MODELS` dict에 신규 도구 입력 모델 추가. 백엔드의 `mcp_tool` 마이그레이션 생성에 쓰인다.
- [ ] **(9) 단위 테스트 작성** — `tests/<같은 위치>/`. 외부 호출은 `monkeypatch`로 격리. 픽스처는 `tests/fixtures/`.
- [ ] **(10) 검증** — §7.6.

체크리스트 항목 중 자기 작업에 해당 없는 것은 건너뛴다. 예: 외부 호출이 없는 도구라면 (4), (7) 건너뛴다.

### 7.5 도구 등록 트리거 (자주 빠지는 함정)

`src/mcp_server/tools/__init__.py`:
```python
from mcp_server.tools import real_estate  # noqa: F401
from mcp_server.tools import <new_module>  # ← 한 줄 추가
```

이 한 줄을 빼먹으면 도구가 mcp 인스턴스에 등록되지 않는다. **`list_tools()`에서 안 보이면 가장 먼저 의심할 곳.**

### 7.6 검증

```bash
# 단위 테스트
uv run pytest -q

# export_tool_schema 동작 확인 (백엔드 마이그레이션에 사용)
uv run python scripts/export_tool_schema.py <new_tool_name>

# MCP 인스턴스에 도구가 실제 등록됐는지 확인
uv run python -c "
import asyncio
from mcp_server.server import mcp
import mcp_server.tools

async def main():
    tools = await mcp.list_tools()
    for t in tools:
        print(t.name)
asyncio.run(main())
"
```

### 7.7 ADR — 작업 시작 전 한 장 작성

코드 시작 전에 `/docs/adr/NNNN-<주제>.md`를 작성한다 (CLAUDE.md "의사결정 기록" 섹션). 다음 항목 정도면 충분:

- **상태**: Accepted / Superseded by …
- **날짜**: YYYY-MM-DD
- **배경**: 왜 이 작업을 MCP 서버에 넣는가 (다른 위치 — 백엔드, 메인 DB — 와 비교해 왜 여기인가)
- **결정**: 어떤 패턴·테이블·도구로 갈 것인가
- **영향**: 새로 생기는 환경변수·테이블·도구·의존성. 백엔드와의 인터페이스 변화.
- **재평가 조건**: 어떤 상황이 오면 이 결정을 뒤집어야 하는가

ADR이 있으면 다른 팀원이 코드 리뷰할 때 "왜 이렇게 짰지?" 묻지 않아도 되고, 6개월 뒤 본인도 이유를 잊지 않는다.

---

## 8. 외부 호출 규약 (외부 API/SDK를 부르는 작업만 해당)

도구 안에서 **`httpx` / 외부 SDK를 직접 import 하지 말 것.** 항상 서비스 레이어 경유.

현재 갖춰진 서비스 레이어:
```python
from mcp_server.sources import api_source_service

raw = await api_source_service.fetch(source_id=..., params={...})

# tool_name 으로 source_id 조회 (도구별 캐시 권장)
source_id = await api_source_service.resolve_source_id_by_tool_name("search_house_price")
```

이유:
- **캐시**(향후 `api_cache` 활용) / 트레이싱 / 에러 분류가 한 곳에 모임
- API 키 같은 비밀값을 도구 코드 안에 박지 않음
- URL 템플릿이 `api_source.url_template` DB 컬럼에서 관리됨 → **엔드포인트 변경 시 DB만 update, 코드 수정 불필요**
- 메인 백엔드는 수집 방식을 알 필요 없음 (CLAUDE.md "절대 지킬 것" 1번)

`fetch()` 반환은 `RawResult` (`sources/result.py`):
```python
class RawResult(BaseModel):
    source_type: Literal["api", "crawl"]   # 현재 도구는 모두 "api"
    source_id: int
    content: str          # 원문 텍스트 또는 JSON 직렬화 문자열
    fetched_at: datetime
    raw_metadata: dict    # tool_name, url_template, params 등
```

도구는 `raw.content`를 도메인 정규화기(`domains/<영역>/`)로 넘겨 모델 list로 변환한 뒤 §6 공통 스키마에 담아 반환.

**외부 호출 종류가 달라 새 서비스 레이어가 필요한 경우** (예: 임베딩 모델 호출, 알림 채널 발송 등):
- `src/mcp_server/sources/<영역>_service.py` 또는 `src/mcp_server/domains/<영역>/service.py` 새 파일
- 같은 패턴: 입력 검증 → 외부 호출 → 결과를 표준 dataclass/pydantic 모델로 반환 → 도구는 이 함수만 호출
- 에러는 도메인 전용 베이스(§9)로 분류. `httpx.HTTPError`를 도구 레이어까지 누출하지 말 것.

---

## 9. 에러 모델

에러는 **두 계층**으로 나뉜다. 분류가 곧 재시도 정책의 근거.

### 9.1 sources 레이어 (`sources/errors.py`)

| 예외 | 의미 | 재시도 |
|---|---|---|
| `SourceNotFoundError` | 등록되지 않은 `source_id` (시드 누락) | ❌ 재시도 무의미 — 운영자가 시드해야 함 |
| `SourceFetchError` | 네트워크/HTTP 오류 (4xx/5xx, 타임아웃, DNS 실패 등) | ✅ 백오프 후 재시도 의미 있음 |
| `SourceNotImplementedError` | 해당 경로 구현이 아직 없음 (현재는 크롤링 경로 — §14) | ❌ 구현 머지 전까지 영원히 실패 |

### 9.2 도메인 레이어 (`domains/<영역>/errors.py`)

각 영역은 자기 도메인 에러 베이스를 가진다. 참고 사례 — real_estate:

| 예외 | 의미 | 재시도 |
|---|---|---|
| `RealEstateConfigError` | `MOLIT_TRADE_API_KEY` 미설정 등 설정 누락 | ❌ 환경변수 채울 때까지 영원히 실패 |
| `RealEstateRegionNotFoundError` | 자연어 region이 LAWD_CD로 변환 불가 (오타 또는 동음이의) | ❌ 입력 자체를 바꿔야 함 (예외에 `candidates: list[(표시명, 코드)]` 동봉) |
| `RealEstateNormalizationError` | API 응답 `resultCode != "000"`, XML 파싱 실패, 필수 필드 누락 | ⚠️ resultCode 별로 분기 (활용신청 미승인은 ❌, 일시 오류는 ✅) |

새 영역 도입 시 동일 패턴: `<영역>Error` 베이스 + `ConfigError` + 영역 고유 에러들.

### 9.3 백엔드 측 매핑 권장

- ❌ 그룹 → HTTP 4xx (구독 자체를 비활성화하거나 운영자에게 알림)
- ✅ 그룹 → 재시도 큐 (`@Scheduled` 다음 사이클에 재시도)
- 모든 도메인 에러는 **메시지에 사용자가 이해할 단서**(예: `RegionNotFoundError.candidates`)를 동봉. 백엔드는 그 메시지를 사용자에게 전달.

---

## 10. 테스트 작성 패턴

### 10.1 픽스처 위치

- 외부 응답 픽스처(XML/JSON): `tests/fixtures/<영역>_<시나리오>.<확장자>`
- in-memory DB: `aiosqlite` 자동 사용 (`patched_session_factory` 픽스처)

### 10.2 도구 테스트 핵심 헬퍼 (참고 사례 — `tests/tools/test_real_estate.py`)

```python
# 1) 환경변수 주입 + Settings 캐시 클리어
monkeypatch.setenv("MOLIT_TRADE_API_KEY", "test-key-12345")
get_settings.cache_clear()

# 2) lazy 캐시 격리 (tool_name → source_id 캐시)
monkeypatch.setattr(real_estate, "_cached_source_id", None)

# 3) DB 시드
await _seed_source(url_template="https://example.gov/...")

# 4) fetch stub (외부 호출 격리)
captured = {}
monkeypatch.setattr(api_source_service, "fetch", _make_fake_fetch(SAMPLE_XML, captured))

# 5) 도구 호출 + 검증
result = await search_house_price(SearchHousePriceInput(region="강남구", deal_ymd="202403"))
assert result["structured"]["summary"]["count"] == 2
```

같은 5단계 패턴(env 주입 → 캐시 격리 → DB 시드 → 외부 호출 stub → 호출·검증)이 영역에 무관하게 적용된다.

### 10.3 도메인 로직 테스트 (참고 사례 — `tests/domains/real_estate/test_normalizer.py`)

순수 함수면 외부 의존 없음. 픽스처를 `_load("...")`로 읽어 변환 결과만 검증.

### 10.4 실행

```bash
uv run pytest -q                                       # 전체
uv run pytest tests/tools/ -v                          # 특정 디렉토리
uv run pytest tests/tools/test_real_estate.py::test_X  # 특정 케이스
```

---

## 11. 트러블슈팅

| 증상 | 원인 / 해결 |
|---|---|
| `<영역>ConfigError: <KEY> 미설정` | `.env`에 환경변수 누락. 키 발급 또는 값 확인 후 채우기. |
| **SSE 클라이언트가 `list_tools`에서 빈 배열 받음** | `python -m mcp_server.server`로 띄웠을 가능성 (§4.2). 표준 명령 `python -m mcp_server` 또는 `uv run mcp-server` 사용. |
| `relation "..." does not exist` | §3.4의 `create_tables.py` 미실행. 새 모델 추가했으면 다시 실행. |
| `SourceNotFoundError: api_source.tool_name=... 등록되지 않음` | 시드 누락. 해당 영역 `seed_*.py` 실행. |
| 외부 API `resultCode != "000"` 또는 4xx/5xx | 활용 신청 미승인 / 일일 호출 한도 초과 / 키 만료. 해당 사이트 마이페이지 확인. |
| `password authentication failed for user "devuser"` | §3.4의 롤 비밀번호와 `.env`의 `PG_URL` 비밀번호 불일치. `ALTER ROLE devuser WITH PASSWORD 'devpass';`로 동기화. |
| `pytest`가 0개 테스트 발견 | `mcp-server/` 디렉터리에서 실행했는지 확인. |
| 도구 추가했는데 `list_tools`에 안 보임 | `tools/__init__.py`에 `from mcp_server.tools import <new_module>` 추가했는지 확인 (§7.5). |
| 백엔드 측 `mcp_tool` 마이그레이션에 신규 도구 누락 | `scripts/export_tool_schema.py`의 `_TOOL_INPUT_MODELS`에 등록했는지 확인 (§7.4 체크리스트 8단계). |
| Langfuse trace가 안 보임 | `LANGFUSE_ENABLED=true` + `LANGFUSE_PUBLIC_KEY/SECRET_KEY/HOST` 모두 설정. `flush_langfuse()`는 lifespan shutdown에서만 호출되므로 stdio 모드 짧은 실행에선 trace 손실 가능. |

---

## 12. 디렉토리 구조

```
mcp-server/
  src/mcp_server/
    __main__.py             # python -m mcp_server 표준 엔트리 (§4.2 함정 회피용)
    server.py               # FastMCP 인스턴스 + main() + /health
    config.py               # pydantic-settings (Settings 클래스)
    tools/                  # @mcp.tool() 등록 — 영역별 1파일
      __init__.py           # ← 도구 자동 등록 트리거 (§7.5)
      real_estate.py        # 참고 사례 (외부 데이터 조회 도구)
    sources/                # 외부 호출 서비스 레이어 (§8)
      api_source_service.py
      crawl_source_service.py  # 골격만 — 현재 미사용 (§14)
      result.py             # RawResult
      errors.py             # SourceError 계열
    domains/                # 영역별 정규화기 / 헬퍼 / 도메인 에러
      real_estate/          # 참고 사례
        normalizer.py       # XML/JSON → Pydantic 모델 list
        errors.py           # 도메인 전용 에러
        region.py           # 자연어 region → LAWD_CD
    db/                     # SQLAlchemy 모델 / 세션 (§3.4)
      models.py             # ApiSource / ApiCache (CrawlSource/CrawlCache 는 DDL 골격만 — §14)
      session.py            # async session factory
    observability/
      tracing.py            # @traced (Langfuse 래퍼)
  scripts/
    create_tables.py        # MCP 내부 DB 스키마 생성 (§3.4)
    seed_*.py               # 영역별 시드 (참고: seed_real_estate_source.py)
    export_tool_schema.py   # mcp_tool.input_schema 마이그레이션용 JSON Schema export
  tests/
    fixtures/               # 외부 응답 XML/JSON 픽스처 (§10.1)
    domains/                # 도메인 로직 단위 테스트
    tools/                  # 도구 통합 테스트
    sources/                # sources/ 서비스 테스트
  data/
    lawd_codes.json         # 정적 매핑 데이터 (real_estate 전용)
  pyproject.toml
  .env.example
  docs/
    team-guide.md           # 본 문서
    adr/                    # ADR (§7.7)
```

---

## 13. 더 깊이 알아보기

- **프로젝트 원칙 / Tool 설계 / Anti-pattern**: 루트 `CLAUDE.md`
- **의사결정 기록(ADR)**: `docs/adr/NNNN-*.md` (작성 가이드는 CLAUDE.md "의사결정 기록" 섹션, 본 가이드 §7.7)
- **Spring AI MCP Client 공식 문서**: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client.html>
- **MCP 공식 스펙**: <https://modelcontextprotocol.io/>
- **운영 배포(Dockerfile / CI / docker-compose)**: 본 문서는 **로컬 개발 + 백엔드 통합**까지 다룬다. 인프라는 별도 문서.

---

## 14. 향후 확장: 크롤링 지원 (현재 미구현)

설계상 크롤링은 공공 API와 동등한 1급 자료원이지만, 현재는 **골격 코드와 DB DDL만 존재**하고 활성화되지 않았다. 채용 등 공공 API가 빈약한 영역을 다룰 때 활성화한다.

### 14.1 이미 갖춰져 있는 것

| 위치 | 내용 | 상태 |
|---|---|---|
| `src/mcp_server/sources/crawl_source_service.py` | `fetch(source_id, params) → RawResult` 시그니처와 흐름 골격 | `_render()` 미구현 → `SourceNotImplementedError` |
| `src/mcp_server/db/models.py` | `CrawlSource` (base_url, css_selector, headers, is_active) / `CrawlCache` 모델 | `create_tables.py` 가 DDL 생성 |
| `pyproject.toml` | `playwright`, `beautifulsoup4`, `trafilatura` 의존성 | `uv sync` 시 함께 설치됨 |
| `sources/result.py:RawResult.source_type` | `Literal["api", "crawl"]` — 타입 시스템에 이미 반영 | — |

### 14.2 활성화에 필요한 작업

1. `crawl_source_service._render()` 구현 — Playwright 또는 httpx + BeautifulSoup/trafilatura 분기 (CSR 사이트는 Playwright 필수)
2. Playwright 브라우저 설치: `uv run playwright install chromium`
3. `crawl_cache` read/write 활성화 (도메인별 TTL 가이드: CLAUDE.md "캐시 TTL 가이드")
4. 영역별 시드 스크립트에 `CrawlSource` upsert 추가
5. 도구 작성 시 `crawl_source_service.fetch()` 경유 (§8 패턴 동일)
6. **봇 방지 대응** — User-Agent 로테이션 / 요청 간격 / 캡차 회피 정책 결정 (CLAUDE.md "크롤링 대상별 전략" 참조)
7. ADR 작성: 어떤 사이트를 어떤 방식(Playwright vs httpx)으로 크롤링하는지

활성화 전까지는 본 문서 §1~§13이 단일 진실 원천. 코드에서 `CrawlSource` / `crawl_source_service` / `playwright` 등을 마주쳐도 **현재는 사용하지 않는 코드**라고 이해하면 됨.

---

## 15. 변경 이력

이 문서를 수정할 때:
- §3 환경변수 표는 `.env.example`과 동기화
- §6 응답 스키마는 새 도구가 추가되어 통계 필드가 늘어나면 갱신
- §7.2 디렉토리·파일 매핑 표는 새 위치/패턴이 도입되면 한 행씩 추가
- §9 에러 표는 새 영역 추가 시 도메인 에러 행 추가
- §11 트러블슈팅 표는 자주 빠지는 함정이 새로 발견되면 한 행씩 추가
- §14는 크롤링 활성화 시 본문(§8, §12 등)에 통합하고 이 섹션은 짧게 정리
- 코드 인용은 파일경로:라인 형식 유지 (라인 번호 자주 바뀌니 주변 문맥 함께)