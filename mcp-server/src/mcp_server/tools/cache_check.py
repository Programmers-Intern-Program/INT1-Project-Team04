"""캐시 상태 확인 도구.

fetch tool 호출 전 AI 가 이 도구를 먼저 호출해 캐시 신선도를 판단한다.
"""

from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.sources import api_source_service


@mcp.tool()
@traced("check_api_cache")
async def check_api_cache(tool_name: str, params: dict) -> dict:
    """데이터 조회 시 반드시 가장 먼저 호출. fetch tool보다 선행 필수.

    cache_hit, cached_at, tool_name, content 를 반환한다.
    AI 는 이 결과와 tool_name 에서 추론한 도메인 성격을 바탕으로
    실제 fetch 가 필요한지 스스로 판단한다.
    """
    return await api_source_service.check_cache(tool_name, params)