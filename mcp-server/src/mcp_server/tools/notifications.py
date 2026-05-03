"""알림 발송 MCP 도구.

등록 도구 (1종):
- send_notification — 구독 조건 충족 시 Telegram DM / Discord DM / Email 발송

원칙:
- notificationChannel / notificationTarget 은 백엔드 SubscriptionContext 값을 그대로 쓴다.
- 실제 외부 알림 발송은 이 도구가 수행한다. 모델의 자연어 응답만으로는 발송되지 않는다.
- 발송 성공 여부는 structured.sent 로만 판단한다.
- provider token / SMTP password 같은 비밀값은 도구 인자로 받지 않고 settings 에서 읽는다.
"""

from typing import Any

from mcp_server.config import get_settings
from mcp_server.notifications.delivery import NotificationDeliveryService
from mcp_server.notifications.models import NotificationRequest
from mcp_server.observability.tracing import traced
from mcp_server.server import mcp

_TOOL_SEND_NOTIFICATION = "send_notification"


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
    자연어 최종 응답을 발송으로 간주하지 말고, structured.sent 가 true 일 때만
    발송 성공으로 판단한다.
    """
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
        },
    }
