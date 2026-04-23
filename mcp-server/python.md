## 처음 환경 설정 (Python이 처음이라면)

> 백엔드(Java) / 프론트(TypeScript) 담당자가 mcp-server 작업해야 할 때 참고. 이미 Python 익숙하면 [빠른 시작](#빠른-시작)으로 건너뛰세요.

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
- `pyproject.toml` + `uv.lock` 기준으로 의존성 설치 (`mcp`, `httpx`, `playwright`, ...)

> **개념 매핑**: `uv sync` ≈ `npm install` ≈ `./gradlew build`

### 4. 환경 변수 파일 생성

```bash
cp .env.example .env
# .env 안의 값을 채워넣기 (DB 접속, API 키 등)
```

### 5. 테스트로 설치 확인

```bash
uv run pytest
```

**`4 passed` 가 떨어지면 환경 설정 완료.**

> `uv run <명령>` 은 `.venv` 활성화된 상태로 명령 실행. 매번 `source .venv/bin/activate` 안 해도 됨.

---

## 빠른 시작

```bash
cd mcp-server

# 의존성 설치
uv sync

# 환경 변수
cp .env.example .env
# .env 안의 값을 채워넣을 것

# 테스트
uv run pytest

# (Phase 2 이후) MCP 서버 기동 — stdio
uv run python -m mcp_server.server

# (Phase 2 이후) MCP Inspector 로컬 접속
npx @modelcontextprotocol/inspector uv run python -m mcp_server.server
```

요구사항: Python 3.11+, [uv](https://docs.astral.sh/uv/), PostgreSQL 16+ (pgvector).

---