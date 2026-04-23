"""스모크 테스트.

목적: 스캐폴드가 정상 설치되었는지 최소 검증.
- mcp_server 패키지 import 가능
- 주요 의존성(mcp, sqlalchemy, langfuse, pydantic) import 가능
- pytest-asyncio 동작 확인
"""

import pytest


def test_mcp_server_package_importable():
    """mcp_server 패키지가 import 가능하고 버전 문자열을 노출한다."""
    import mcp_server

    assert mcp_server.__version__ == "0.1.0"


def test_subpackages_importable():
    """하위 패키지들이 모두 import 가능하다."""
    import mcp_server.db  # noqa: F401
    import mcp_server.domains  # noqa: F401
    import mcp_server.observability  # noqa: F401
    import mcp_server.sources  # noqa: F401
    import mcp_server.tools  # noqa: F401


def test_core_dependencies_importable():
    """Phase 1~3에서 쓸 핵심 의존성이 설치되었다."""
    import httpx  # noqa: F401
    import langfuse  # noqa: F401
    import mcp  # noqa: F401
    import pgvector  # noqa: F401
    import pydantic  # noqa: F401
    import sqlalchemy  # noqa: F401


@pytest.mark.asyncio
async def test_pytest_asyncio_works():
    """pytest-asyncio 가 async 테스트를 실행한다."""

    async def echo(value: int) -> int:
        return value

    assert await echo(42) == 42
