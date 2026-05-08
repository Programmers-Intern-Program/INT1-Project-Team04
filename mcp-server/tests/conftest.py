"""테스트 공용 fixture.

SQLite in-memory 비동기 DB 를 사용해 모델/세션을 격리 테스트한다.
실제 Postgres 통합 테스트는 별도 (testcontainers)
"""

# 주의: Settings 의 pg_url 은 default 가 제거되어 .env 미로드 시 ValidationError.
# 테스트 환경에서 mcp_server.server 모듈 import 시점 (collection) 에도 값이 필요하므로
# monkeypatch fixture 보다 먼저, conftest.py 로드 직후 환경변수를 선점한다.
# patched_session_factory 가 실제 엔진을 교체하므로 여기 값은 placeholder.
import os

os.environ.setdefault("PG_URL", "postgresql+asyncpg://test:test@localhost:5432/test")

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from mcp_server.config import get_settings

# models import 로 Base.metadata 등록
from mcp_server.db import models  # noqa: F401
from mcp_server.db import session as db_session
from mcp_server.db.base import Base
from mcp_server.observability.tracing import get_langfuse


@pytest.fixture(autouse=True)
def disable_langfuse(monkeypatch: pytest.MonkeyPatch):
    """모든 테스트에서 Langfuse 비활성 + 테스트용 PG_URL 주입.

    PG_URL 은 config.py 에서 default 가 제거되어 운영에서 .env 미로드 시 즉시 실패하도록 바뀜.
    테스트는 patched_session_factory 로 실제 엔진을 교체하므로 여기서 설정하는 값은 placeholder.
    활성화 검증이 필요한 테스트는 fixture 안에서 monkeypatch 로 다시 켤 것.
    """
    monkeypatch.setenv("PG_URL", "postgresql+asyncpg://test:test@localhost:5432/test")
    monkeypatch.setenv("LANGFUSE_ENABLED", "false")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "")
    get_settings.cache_clear()
    get_langfuse.cache_clear()
    yield
    get_settings.cache_clear()
    get_langfuse.cache_clear()


@pytest_asyncio.fixture
async def in_memory_engine():
    """테스트용 SQLite in-memory async 엔진. 테스트 시작 시 테이블 생성."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", future=True)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest_asyncio.fixture
async def patched_session_factory(
    monkeypatch: pytest.MonkeyPatch, in_memory_engine
) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """db.session.get_session() 이 in-memory 엔진을 쓰도록 패치."""
    factory = async_sessionmaker(bind=in_memory_engine, expire_on_commit=False, autoflush=False)
    # 전역 캐시를 모두 무력화하고 우리 factory 를 박는다
    monkeypatch.setattr(db_session, "_engine", in_memory_engine)
    monkeypatch.setattr(db_session, "_session_factory", factory)
    yield factory
    # 테스트 종료 후 모듈 전역 상태 원복 (다음 테스트 격리)
    monkeypatch.setattr(db_session, "_engine", None)
    monkeypatch.setattr(db_session, "_session_factory", None)
