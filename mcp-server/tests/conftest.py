"""테스트 공용 fixture.

SQLite in-memory 비동기 DB 를 사용해 모델/세션을 격리 테스트한다.
실제 Postgres 통합 테스트는 별도 (testcontainers)
"""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

# models import 로 Base.metadata 등록
from mcp_server.db import models  # noqa: F401
from mcp_server.db import session as db_session
from mcp_server.db.base import Base


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
