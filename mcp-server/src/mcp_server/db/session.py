"""비동기 SQLAlchemy 세션 관리.

규약:
- 모든 도구/서비스는 직접 create_async_engine 을 호출하지 말 것
- DB 작업이 필요하면 `async with get_session() as session:` 패턴 사용
- 엔진은 프로세스당 1회만 생성 (lazy singleton)
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from mcp_server.config import get_settings

_engine: AsyncEngine | None = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def get_engine() -> AsyncEngine:
    """프로세스당 1개의 AsyncEngine을 반환."""
    global _engine
    if _engine is None:
        settings = get_settings()
        _engine = create_async_engine(
            settings.pg_url,
            pool_pre_ping=True,
            future=True,
        )
    return _engine


def get_session_factory() -> async_sessionmaker[AsyncSession]:
    """async_sessionmaker 싱글턴."""
    global _session_factory
    if _session_factory is None:
        _session_factory = async_sessionmaker(
            bind=get_engine(),
            expire_on_commit=False,
            autoflush=False,
        )
    return _session_factory


@asynccontextmanager
async def get_session() -> AsyncIterator[AsyncSession]:
    """비동기 DB 세션 컨텍스트 매니저.

    예외 발생 시 자동 rollback, 성공 시 커밋은 호출자가 명시한다.
    """
    factory = get_session_factory()
    async with factory() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise


async def reset_engine() -> None:
    """테스트 격리용. 엔진/세션 팩토리를 닫고 None 으로 리셋."""
    global _engine, _session_factory
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _session_factory = None


__all__ = [
    "get_engine",
    "get_session",
    "get_session_factory",
    "reset_engine",
]
