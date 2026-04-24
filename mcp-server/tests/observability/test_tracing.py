"""Langfuse 트레이싱 래퍼 단위 테스트.

활성 상태 trace 송출 검증은 deferred (Langfuse v4 OTLP 기반 — mock 비용 큼).
실제 송출은 Langfuse UI 수동 검증으로 처리.
"""

import pytest

from mcp_server.config import get_settings
from mcp_server.observability import tracing


def _clear_caches() -> None:
    get_settings.cache_clear()
    tracing.get_langfuse.cache_clear()


def test_get_langfuse_returns_none_when_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LANGFUSE_ENABLED", "false")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "pk")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "sk")
    _clear_caches()

    assert tracing.get_langfuse() is None


def test_get_langfuse_returns_none_when_keys_missing(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setenv("LANGFUSE_ENABLED", "true")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "")
    _clear_caches()

    with caplog.at_level("WARNING"):
        assert tracing.get_langfuse() is None
    assert any("Langfuse 비활성" in rec.message for rec in caplog.records)


async def test_traced_async_passthrough_when_disabled() -> None:
    @tracing.traced("noop_async")
    async def echo(x: int) -> int:
        return x * 2

    assert await echo(3) == 6


def test_traced_sync_passthrough_when_disabled() -> None:
    @tracing.traced("noop_sync")
    def add(a: int, b: int) -> int:
        return a + b

    assert add(1, 2) == 3


async def test_traced_async_propagates_exception_when_disabled() -> None:
    @tracing.traced("raise")
    async def boom() -> None:
        raise RuntimeError("kaboom")

    with pytest.raises(RuntimeError, match="kaboom"):
        await boom()


def test_flush_langfuse_no_op_when_disabled() -> None:
    tracing.flush_langfuse()
