"""FastMCP 서버 인스턴스 + 엔트리포인트 단위 테스트."""

import pytest
from mcp.server.fastmcp import FastMCP
from pydantic import ValidationError
from starlette.testclient import TestClient

from mcp_server import server as server_mod
from mcp_server.config import Settings, get_settings


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

    async with server_mod.server_lifespan(server_mod.mcp):
        pass

    assert called["reset"] is True
    assert called["flush"] is True


def test_settings_rejects_unknown_transport(monkeypatch: pytest.MonkeyPatch) -> None:
    """Unknown transport 는 Settings 인스턴스화 단계에서 pydantic Literal 검증으로 거부된다."""
    monkeypatch.setenv("MCP_TRANSPORT", "websocket")
    get_settings.cache_clear()
    with pytest.raises(ValidationError):
        Settings()


def test_health_endpoint_returns_ok() -> None:
    """SSE app 에 마운트된 /health 가 200 + {"status":"ok"} 를 돌려준다."""
    app = server_mod.mcp.sse_app()
    with TestClient(app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
