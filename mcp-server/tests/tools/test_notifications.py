"""알림 MCP 도구 입력 모델과 등록 응답 스키마 테스트."""

from __future__ import annotations

from mcp_server.notifications.models import (
    NotificationChannel,
    NotificationRequest,
    NotificationResult,
)
from mcp_server.tools import notifications


def test_notification_request_accepts_backend_field_names() -> None:
    request = NotificationRequest.model_validate({
        "notificationChannel": "TELEGRAM_DM",
        "notificationTarget": "123456",
        "subscriptionId": 42,
        "message": "조건이 충족되었습니다.",
    })

    assert request.channel == NotificationChannel.TELEGRAM_DM
    assert request.target == "123456"
    assert request.subscription_id == 42


def test_notification_request_accepts_tool_alias_field_names() -> None:
    request = NotificationRequest.model_validate({
        "channel": "EMAIL",
        "target": "user@example.com",
        "subscription_id": 42,
        "message": "조건이 충족되었습니다.",
    })

    assert request.channel == NotificationChannel.EMAIL
    assert request.target == "user@example.com"
    assert request.subscription_id == 42


async def test_send_notification_returns_structured_result(monkeypatch) -> None:
    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        return NotificationResult(
            sent=True,
            channel=request.channel,
            target=request.target,
            provider="telegram",
            provider_message_id="123",
            retryable=False,
            status_code=200,
            error=None,
        )

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.TELEGRAM_DM,
        target="123456",
        subscription_id=42,
        idempotency_key="subscription-42-test",
        message="조건이 충족되었습니다.",
    ))

    assert response["text"] == "telegram notification sent."
    assert response["structured"]["sent"] is True
    assert response["structured"]["provider"] == "telegram"
    assert response["metadata"]["tool_name"] == "send_notification"
    assert response["metadata"]["subscription_id"] == 42
    assert response["metadata"]["idempotency_key"] == "subscription-42-test"


def test_notification_request_rejects_invalid_channel() -> None:
    try:
        NotificationRequest.model_validate({
            "notificationChannel": "SMS",
            "notificationTarget": "123456",
            "message": "조건이 충족되었습니다.",
        })
    except ValueError as exc:
        assert "TELEGRAM_DM" in str(exc)
    else:
        raise AssertionError("invalid channel should fail validation")
