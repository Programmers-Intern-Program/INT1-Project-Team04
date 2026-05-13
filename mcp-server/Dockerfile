FROM python:3.11-slim

# 1. uv 설치 (안정적인 빌드를 위해 특정 버전 고정)
COPY --from=ghcr.io/astral-sh/uv:0.5.29 /uv /uvx /usr/local/bin/

# 2. 작업 디렉토리 설정
WORKDIR /app

# 3. 환경 변수 설정 (Python 최적화, uv 캐시 모드, 가상환경 PATH, MCP 설정 통합)
ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy \
    MCP_TRANSPORT=sse \
    MCP_SSE_HOST=0.0.0.0 \
    MCP_SSE_PORT=8090

# 4. 필수 시스템 패키지 설치
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 5. 의존성 파일 복사 및 설치 (소스 코드 변경 시 캐시 무효화를 방지하기 위해 분리)
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

# 6. Playwright 브라우저 및 OS 의존성 설치
# (주의: uv sync로 playwright가 설치된 이후에 실행해야 합니다)
RUN playwright install --with-deps chromium

# 7. 프로젝트 소스 복사 및 최종 설치
COPY src/ ./src/
COPY data/ ./data/
COPY alembic/ ./alembic/
COPY alembic.ini ./
COPY scripts/ ./scripts/
RUN uv sync --frozen --no-dev

# 8. 포트 노출 및 실행
EXPOSE 8090

# PATH가 등록되어 있으므로 uv run 없이 직접 실행 가능 (프로세스 시그널 처리에 유리함)
CMD ["python", "-m", "mcp_server"]
# 만약 pyproject.toml의 [project.scripts]에 mcp-server가 등록되어 있다면 아래 명령어 사용:
# CMD ["mcp-server"]