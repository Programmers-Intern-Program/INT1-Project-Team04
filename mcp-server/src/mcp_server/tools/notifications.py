"""알림 발송 MCP 도구.

등록 도구 (1종):
- send_notification — 구독 조건 충족 시 Telegram DM / Discord DM / Email 발송

원칙:
- notificationChannel / notificationTarget 은 백엔드 SubscriptionContext 값을 그대로 쓴다.
- 실제 외부 알림 발송은 이 도구가 수행한다. 모델의 자연어 응답만으로는 발송되지 않는다.
- 구독 실행 알림은 channel-v1 브리핑 렌더링까지 성공해야 발송 성공 증빙으로 쓴다.
- provider token / SMTP password 같은 비밀값은 도구 인자로 받지 않고 settings 에서 읽는다.
"""

from typing import Any

from pydantic import ValidationError

from mcp_server.config import get_settings
from mcp_server.notifications.briefing_models import NotificationBriefing
from mcp_server.notifications.briefing_renderers import render_for_channel
from mcp_server.notifications.delivery import NotificationDeliveryService
from mcp_server.notifications.models import NotificationRequest
from mcp_server.notifications.models import NotificationResult
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp

_TOOL_SEND_NOTIFICATION = "send_notification"
_BRIEFING_CONTRACT_VERSION = "channel-v1"


@mcp.tool()
@traced(_TOOL_SEND_NOTIFICATION)
async def send_notification(input: NotificationRequest) -> dict[str, Any]:
    """구독 알림을 Telegram DM, Discord DM, Email 로 발송한다.

    백엔드 구독 실행 프롬프트가 아래처럼 지시할 때 이 도구를 호출한다.
    "조건 충족 시 notificationChannel과 notificationTarget으로 알림 발송".

    필수 라우팅 필드는 SubscriptionContext 에서 온다.
      - notificationChannel 또는 channel: TELEGRAM_DM, DISCORD_DM, EMAIL
      - notificationTarget 또는 target: 외부 제공자 수신 주소/ID
      - subscriptionId 또는 subscription_id: 구독 ID

    MCP 기반 구독 실행 흐름에서 실제 알림 발송 부작용은 이 도구만 수행한다.
    자연어 최종 응답을 발송으로 간주하지 말고, 구독 실행 알림은 channel-v1
    브리핑 렌더링 결과까지 발송 성공 증빙으로 판단한다.
    """
    briefing_rendered = False
    briefing_contract_version = input.metadata.get("briefingContractVersion")
    if briefing_contract_version != _BRIEFING_CONTRACT_VERSION and _requires_briefing_contract(input):
        return _briefing_contract_failure(
            input,
            "briefing_contract_required",
            "subscription execution notification requires channel-v1 briefing metadata",
        )

    if briefing_contract_version == _BRIEFING_CONTRACT_VERSION:
        try:
            briefing = NotificationBriefing.model_validate(input.metadata.get("briefing"))
            rendered = render_for_channel(briefing, input.channel)
        except ValidationError as exc:
            return _briefing_contract_failure(input, "briefing_contract_invalid", str(exc))
        input = input.model_copy(update={
            "title": rendered.provider_title,
            "message": rendered.message,
        })
        briefing_rendered = True

    result = await NotificationDeliveryService(get_settings()).send(input)
    status_text = "sent" if result.sent else "failed"
    return {
        "text": f"{result.provider} notification {status_text}.",
        "structured": result.model_dump(mode="json"),
        "source_url": None,
        "metadata": {
            "tool_name": _TOOL_SEND_NOTIFICATION,
            "subscription_id": input.subscription_id,
            "idempotency_key": input.idempotency_key,
            "briefing_contract_version": briefing_contract_version,
            "briefing_rendered": briefing_rendered,
        },
    }


def _requires_briefing_contract(input: NotificationRequest) -> bool:
    """AI 구독 실행의 직접 발송만 channel-v1 계약을 강제한다."""
    return input.subscription_id is not None and not _is_backend_delivery(input.metadata)


def _is_backend_delivery(metadata: dict[str, Any]) -> bool:
    """백엔드 NotificationDelivery 경로는 이미 서버가 본문을 조립하므로 legacy 발송을 허용한다."""
    return bool(metadata.get("deliveryId")) and bool(metadata.get("alertEventId"))


def _briefing_contract_failure(input: NotificationRequest, error: str, detail: str) -> dict[str, Any]:
    """channel-v1 브리핑 계약 위반은 provider 발송 전에 구조화 실패로 반환한다."""
    result = NotificationResult(
        sent=False,
        channel=input.channel,
        target=input.target,
        provider="briefing_contract",
        retryable=False,
        error=error,
    )
    return {
        "text": "briefing_contract notification failed.",
        "structured": result.model_dump(mode="json"),
        "source_url": None,
        "metadata": {
            "tool_name": _TOOL_SEND_NOTIFICATION,
            "subscription_id": input.subscription_id,
            "idempotency_key": input.idempotency_key,
            "briefing_contract_version": input.metadata.get("briefingContractVersion"),
            "briefing_rendered": False,
            "briefing_error": detail,
        },
    }
