"""구독 MCP 도구.

등록 도구:
- compare_subscription_change : 조회 결과와 구독 baseline/current 를 비교한다.
- normalize_subscription_draft: 백엔드 AI 파서 초안을 도메인별 실행 계약으로 구조화한다.

원칙:
- 도구 응답은 다른 MCP 도구와 동일하게 {text, structured, source_url, metadata} 공통 스키마를 유지한다.
- normalize_subscription_draft 는 구독을 저장하지 않는다. 저장 가능 여부 판단에 필요한
  structured.missingFields 와 structured.parameters 만 백엔드에 돌려준다.
"""

from typing import Any

from mcp_server.observability.tracing import traced
from mcp_server.server import mcp
from mcp_server.subscriptions.change_models import SubscriptionChangeInput
from mcp_server.subscriptions.change_service import SubscriptionChangeService
from mcp_server.subscriptions.draft_models import SubscriptionDraftNormalizationInput
from mcp_server.subscriptions.draft_normalizer import (
    normalize_subscription_draft as normalize_subscription_draft_service,
)

_TOOL_COMPARE_SUBSCRIPTION_CHANGE = "compare_subscription_change"
_TOOL_NORMALIZE_SUBSCRIPTION_DRAFT = "normalize_subscription_draft"


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


@mcp.tool()
@traced(_TOOL_NORMALIZE_SUBSCRIPTION_DRAFT)
async def normalize_subscription_draft(input: SubscriptionDraftNormalizationInput) -> dict[str, Any]:
    """AI 파서 초안을 도메인별 실행 계약으로 정규화한다.

    백엔드 SubscriptionConversationService 는 이 결과를 다시 cadence/channel/endpoint 정보와
    합성한 뒤, missingFields 가 비어 있을 때만 확인 화면으로 넘긴다.
    """
    result = normalize_subscription_draft_service(input)
    text = (
        "구독 초안 구조화 완료."
        if not result.missing_fields
        else "구독 초안 구조화에 추가 정보가 필요합니다."
    )

    return {
        "text": text,
        "structured": result.model_dump(mode="json", by_alias=True),
        "source_url": None,
        "metadata": {
            "tool_name": _TOOL_NORMALIZE_SUBSCRIPTION_DRAFT,
            "missing_fields": result.missing_fields,
        },
    }
