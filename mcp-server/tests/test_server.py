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


async def test_registered_tools_include_search_house_price() -> None:
    """tools/__init__.py 의 명시 import 가 실제로 도구를 mcp 인스턴스에 등록하는지 확인.

    도구가 늘어나면 이 set 에 이름을 추가하라.
    """
    # 명시 import — server.main() 안의 lazy import 와 같은 효과를 단위 테스트에서 강제.
    import mcp_server.tools  # noqa: F401

    tools = await server_mod.mcp.list_tools()
    names = {t.name for t in tools}
    assert "search_house_price" in names


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


def test_package_main_module_delegates_to_server_main() -> None:
    """`python -m mcp_server` 표준 엔트리가 server.main 으로 위임되어야 한다.

    회귀 방지: 과거 `python -m mcp_server.server` 가 server.py 를 `__main__` 으로
    로드해 mcp 인스턴스가 이중화되어 SSE list_tools 가 [] 를 반환하던 함정을 막는다.
    `mcp_server/__main__.py` 가 server 를 모듈로만 import 하면 인스턴스는 단일화된다.
    """
    import mcp_server.__main__ as entry

    assert entry.main is server_mod.main
