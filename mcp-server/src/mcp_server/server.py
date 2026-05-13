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
from mcp_server.sources.api_source_service import aclose_http_client


@asynccontextmanager
async def server_lifespan(_: FastMCP) -> AsyncIterator[None]:
    # startup: Langfuse 클라이언트 워밍업 (비활성이면 None 반환, 무영향)
    get_langfuse()
    try:
        yield
    finally:
        # shutdown: trace 손실 방지 + 공유 HTTP 클라이언트 / DB 풀 정리
        flush_langfuse()
        await aclose_http_client()
        await reset_engine()


_settings = get_settings()

mcp: FastMCP = FastMCP(
    name="monitoring-mcp",
    instructions=(
        "부동산 / 법률 / 채용 / 경매 4개 도메인의 변화를 감시하는 MCP 서버. "
        "도구 호출 결과는 {text, structured, source_url, metadata} 공통 스키마로 반환된다. "
        "구독 변화 감시는 데이터 조회 도구를 먼저 호출한 뒤 compare_subscription_change 로 "
        "baseline 과 current 를 비교하고, structured.briefing_facts 로 알림 본문을 구성한 다음 "
        "send_notification 을 호출하는 흐름을 따른다. compare_subscription_change 결과의 "
        "structured.baseline_initialized 가 true 이면 baseline 초기화만 수행된 것이므로 "
        "알림을 발송하지 않는다. "
        "구독 조건이 충족되어 notificationChannel 과 notificationTarget 으로 알림을 "
        "발송해야 할 때는 반드시 send_notification 도구를 호출한다. 자연어 응답만으로는 "
        "알림이 발송되지 않으며, send_notification 결과의 structured.sent 가 true 일 때만 "
        "발송 성공으로 판단한다."
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

    실행은 항상 `python -m mcp_server` (또는 console_script `mcp-server`) 로 한다.
    `python -m mcp_server.server` 는 server.py 를 `__main__` 으로 로드해 도구 모듈이
    참조하는 mcp 인스턴스와 별개 객체가 생기는 함정이 있어 의도적으로 막아 두었다
    (이 모듈 하단에 `if __name__ == "__main__"` 블록을 두지 않는다).
    """
    # 도구 등록 트리거 — top-level 이 아닌 함수 안 import 로 순환 회피.
    import mcp_server.tools  # noqa: F401

    s = get_settings()
    mcp.run(transport=s.mcp_transport)
