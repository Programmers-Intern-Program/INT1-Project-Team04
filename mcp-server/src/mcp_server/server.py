"""FastMCP 서버 인스턴스 + 엔트리포인트.

규약:
- 모든 도구 모듈은 `from mcp_server.server import mcp` 후 @mcp.tool() 데코레이터 사용.
- 새 FastMCP() 추가 생성 금지 — 인스턴스 분리 시 도구 등록이 보이지 않음.

도구 자동 등록은 `mcp_server.tools.__init__` 이 leaf 도구 모듈을 명시 import 하는
방식으로 한다. 서버 엔트리포인트(main) 가 `import mcp_server.tools` 한 줄로 트리거.
(서버 모듈 top-level 에서 직접 import 하면 도구 모듈의 `from mcp_server.server import mcp`
와 순환하므로 함수 안 lazy import 로 한다.)
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from mcp.server.fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import JSONResponse

from mcp_server.config import get_settings
from mcp_server.db.session import reset_engine
from mcp_server.observability.tracing import flush_langfuse, get_langfuse


@asynccontextmanager
async def server_lifespan(_: FastMCP) -> AsyncIterator[None]:
    # startup: Langfuse 클라이언트 워밍업 (비활성이면 None 반환, 무영향)
    get_langfuse()
    try:
        yield
    finally:
        # shutdown: trace 손실 방지 + DB 풀 정리
        flush_langfuse()
        await reset_engine()


_settings = get_settings()

mcp: FastMCP = FastMCP(
    name="monitoring-mcp",
    instructions=(
        "부동산 / 법률 / 채용 / 경매 4개 도메인의 변화를 감시하는 MCP 서버. "
        "도구 호출 결과는 {text, structured, source_url, metadata} 공통 스키마로 반환된다."
    ),
    host=_settings.mcp_sse_host,
    port=_settings.mcp_sse_port,
    lifespan=server_lifespan,
)


@mcp.custom_route("/health", methods=["GET"])
async def health(_: Request) -> JSONResponse:
    """SSE 모드에서만 노출. stdio 에서는 starlette app 이 mount 되지 않아 무동작."""
    return JSONResponse({"status": "ok"})


def main() -> None:
    """`mcp-server` CLI 엔트리포인트. 환경변수 MCP_TRANSPORT 로 분기.

    transport 값 검증은 config.Settings 의 Literal 타입이 담당 — 잘못된 값은 Settings
    인스턴스화 단계에서 ValidationError 로 거부된다.
    """
    # 도구 등록 트리거 — top-level 이 아닌 함수 안 import 로 순환 회피.
    import mcp_server.tools  # noqa: F401

    s = get_settings()
    mcp.run(transport=s.mcp_transport)


if __name__ == "__main__":
    main()
