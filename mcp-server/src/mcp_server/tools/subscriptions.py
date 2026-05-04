"""구독 변화 비교 MCP 도구."""

from typing import Any

from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.subscriptions.change_models import SubscriptionChangeInput
from mcp_server.subscriptions.change_service import SubscriptionChangeService

_TOOL_COMPARE_SUBSCRIPTION_CHANGE = "compare_subscription_change"


@mcp.tool()
@traced(_TOOL_COMPARE_SUBSCRIPTION_CHANGE)
async def compare_subscription_change(input: SubscriptionChangeInput) -> dict[str, Any]:
    """데이터 조회 도구 결과를 구독 기준값과 비교해 변화 여부를 반환한다."""
    result = await SubscriptionChangeService().compare(input)
    if result.baseline_initialized:
        text = f"구독 {result.subscription_id} baseline snapshot 초기화 완료."
    elif result.changed:
        text = f"구독 {result.subscription_id} 변화 감지: 변경 있음."
    else:
        text = f"구독 {result.subscription_id} 변화 감지: 변경 없음."

    return {
        "text": text,
        "structured": result.model_dump(mode="json", by_alias=True),
        "source_url": None,
        "metadata": {
            "tool_name": _TOOL_COMPARE_SUBSCRIPTION_CHANGE,
            "subscription_id": result.subscription_id,
            "params_hash": result.params_hash,
        },
    }
