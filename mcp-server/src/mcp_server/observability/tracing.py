"""Langfuse 트레이싱 래퍼.

모든 @mcp.tool() 도구에 부착되는 표준 트레이서를 제공한다.

사용 예시:
    from mcp_server.observability.tracing import traced

    @mcp.tool()
    @traced("search_house_price")
    async def search_house_price(...): ...

비활성 조건 (LANGFUSE_ENABLED=false 또는 키 미설정) 시 데코레이터는
원함수를 그대로 반환하므로 테스트 / 로컬 환경에서 외부 호출이 일어나지 않는다.
"""

import logging
from collections.abc import Callable
from functools import lru_cache
from typing import TypeVar

from langfuse import Langfuse, observe

from mcp_server.config import get_settings

F = TypeVar("F", bound=Callable)
_log = logging.getLogger(__name__)


@lru_cache(maxsize=1)
def get_langfuse() -> Langfuse | None:
    """Langfuse 클라이언트 싱글턴. 비활성 또는 키 미설정 시 None."""
    s = get_settings()
    if not s.langfuse_enabled:
        return None
    if not s.langfuse_public_key or not s.langfuse_secret_key:
        _log.warning("Langfuse 비활성: PUBLIC_KEY / SECRET_KEY 가 비어있음")
        return None
    return Langfuse(
        public_key=s.langfuse_public_key,
        secret_key=s.langfuse_secret_key,
        host=s.langfuse_host,
    )


def flush_langfuse() -> None:
    """버퍼링된 trace 전송. lifespan shutdown 에서 호출해 손실 방지."""
    client = get_langfuse()
    if client is not None:
        client.flush()


def traced(name: str) -> Callable[[F], F]:
    """모든 @mcp.tool() 도구에 부착되는 표준 트레이싱 데코레이터.

    Langfuse 비활성 시 원함수를 그대로 반환 (no-op).
    활성 시 langfuse `@observe(name, as_type="tool")` 위임 — sync/async 자동 처리,
    입력/출력 자동 캡처, 예외 발생 시 status=ERROR.

    민감정보 redaction 은 deferred. 도구 입력에 비밀이 들어갈 경우
    Langfuse `mask=` 콜백 또는 도구 측 sanitize 로 처리.

    시그니처 안정성: 향후 옵션 추가 시 keyword-only 만 허용.
    """
    def decorator(func: F) -> F:
        if get_langfuse() is None:
            return func
        return observe(name=name, as_type="tool")(func)  # type: ignore[return-value]
    return decorator
