"""FastMCP 서버 인스턴스 + 엔트리포인트 단위 테스트.

`/health` 라우트의 실제 응답 검증은 SSE 수동 검증 (README) 으로 처리.
FastMCP `_custom_starlette_routes` 는 비공개 attr 이라 단위 테스트 의존을 피한다.
"""

import pytest
from mcp.server.fastmcp import FastMCP

from mcp_server import server as server_mod
from mcp_server.config import get_settings


def test_mcp_instance_is_fastmcp() -> None:
    assert isinstance(server_mod.mcp, FastMCP)


def test_mcp_name() -> None:
    assert server_mod.mcp.name == "monitoring-mcp"


async def test_no_tools_registered_yet() -> None:
    """Phase 2 시점엔 도구 0개. Phase 3-2 부터 늘어나면 본 assertion 갱신."""
    tools = await server_mod.mcp.list_tools()
    assert tools == []


async def test_lifespan_calls_shutdown_hooks(monkeypatch: pytest.MonkeyPatch) -> None:
    called = {"reset": False, "flush": False}

    async def fake_reset() -> None:
        called["reset"] = True

    def fake_flush() -> None:
        called["flush"] = True

    monkeypatch.setattr(server_mod, "reset_engine", fake_reset)
    monkeypatch.setattr(server_mod, "flush_langfuse", fake_flush)

    async with server_mod._lifespan(server_mod.mcp):
        pass

    assert called["reset"] is True
    assert called["flush"] is True


def test_main_rejects_unknown_transport(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MCP_TRANSPORT", "websocket")
    get_settings.cache_clear()
    with pytest.raises(ValueError, match="Unknown MCP_TRANSPORT"):
        server_mod.main()
