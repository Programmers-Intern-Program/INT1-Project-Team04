## 처음 환경 설정 (Python이 처음이라면)

> 백엔드(Java) / 프론트(TypeScript) 담당자가 mcp-server 작업해야 할 때 참고. 이미 Python 익숙하면 [빠른 시작](#빠른-시작)으로 건너뛰세요.
> MCP 서버의 사용법·도구 추가·아키텍처 등 자세한 내용은 [`docs/team-guide.md`](docs/team-guide.md) 참고.

### 1. Python 3.11+ 설치 확인

```bash
python --version    # 또는 python3 --version
```

`Python 3.11.x` 이상이 안 나오면 설치 필요:

- **Windows**: [python.org 공식 설치](https://www.python.org/downloads/) → 설치 시 **"Add Python to PATH" 반드시 체크**
- **macOS**: `brew install python@3.12`
- **Ubuntu/WSL**: `sudo apt install python3.12 python3.12-venv`

### 2. uv 설치

`uv`는 Python의 `npm`/`gradle`에 해당하는 의존성 관리 도구입니다. (`pip` + `venv` + `poetry` 통합)

```bash
# Windows (PowerShell)
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"

# macOS / Linux / WSL
curl -LsSf https://astral.sh/uv/install.sh | sh
```

설치 후 새 터미널 열고 확인:
```bash
uv --version    # uv 0.5.x 이상
```

### 3. 의존성 설치

```bash
cd mcp-server
uv sync
```

이 명령이 자동으로:
- `.venv/` 가상환경 생성 (이 디렉터리만의 격리된 Python)
- `pyproject.toml` + `uv.lock` 기준으로 의존성 설치 (`mcp`, `httpx`, `playwright`, `sqlalchemy[asyncio]`, `asyncpg`, `pgvector`, `langfuse`, `trafilatura`, ...)

> **개념 매핑**: `uv sync` ≈ `npm install` ≈ `./gradlew build`

### 4. Playwright 브라우저 바이너리 설치 (현재 미사용 — 건너뜀 가능)

크롤링 경로는 현재 미구현 상태(자세한 활성화 절차는 `docs/team-guide.md` §14). 공공 API 도구만 다루는 지금은 이 단계 **건너뛰어도 셋업·테스트 모두 정상 작동**.

향후 크롤링 활성화 시에만 실행:
```bash
uv run playwright install chromium
```

### 5. 환경 변수 파일 생성

```bash
cp .env.example .env
```

`.env` 안에서 최소 다음 값을 채워야 도구가 정상 동작:

| 키 | 용도 | 비고 |
|----|------|------|
| `PG_URL` | MCP 내부 DB (Postgres + pgvector) | 로컬은 `postgresql+asyncpg://devuser:devpass@localhost:5432/mcp_dev` 등으로. 미설정 시 import 단계에서 ValidationError. |
| `MOLIT_TRADE_API_KEY` | 국토교통부 아파트 매매 실거래가 API 키 | data.go.kr 활용 신청 후 발급. 비워둬도 import/단위 테스트는 통과, 실제 도구 호출만 거부. |
| `LANGFUSE_ENABLED` | LLM·도구 호출 트레이싱 토글 | 로컬은 `false` 권장 (no-op). `true` 일 때만 PUBLIC/SECRET 키 필요. |
| `MCP_TRANSPORT` | `stdio` (로컬 Inspector) / `sse` (원격 백엔드) | 기본 `stdio`. |

> 전체 키 목록은 `.env.example` / `src/mcp_server/config.py` 의 `Settings` 필드 참고.

### 6. 테스트로 설치 확인

```bash
uv run pytest
```

**전체 테스트가 에러 없이 통과하면 환경 설정 완료.**

> `uv run <명령>` 은 `.venv` 활성화된 상태로 명령 실행. 매번 `source .venv/bin/activate` 안 해도 됨.

---

## 빠른 시작

```bash
cd mcp-server

# 의존성 설치
uv sync

# 환경 변수
cp .env.example .env
# .env 안의 값을 채워넣을 것 (PG_URL, MOLIT_TRADE_API_KEY 등)

# 테스트
uv run pytest

# MCP 서버 기동 — stdio
uv run mcp-server
# 또는 동일
uv run python -m mcp_server

# MCP Inspector 로컬 접속
npx @modelcontextprotocol/inspector uv run mcp-server
```

> **주의**: `python -m mcp_server.server` 로 실행하면 server.py 가 `__main__` 으로 로드되어 FastMCP 인스턴스가 이중 생성됨 → list_tools 가 빈 배열을 반환. 항상 **`uv run mcp-server`** (또는 `python -m mcp_server`) 사용.

요구사항: Python 3.11+, [uv](https://docs.astral.sh/uv/), PostgreSQL (pgvector 확장 활성화).

크롤링 경로는 현재 미구현 — 활성화 절차 및 운영·도구 추가·아키텍처는 [`docs/team-guide.md`](docs/team-guide.md) 참고.

---
