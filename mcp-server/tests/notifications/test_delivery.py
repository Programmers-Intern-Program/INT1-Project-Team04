"""알림 provider 발송 서비스 단위 테스트.

외부 네트워크는 httpx.MockTransport 와 smtplib monkeypatch 로 차단하고,
성공/설정 누락/재시도 가능 실패가 구조화된 NotificationResult 로 반환되는지 검증한다.
"""

from __future__ import annotations

import json
import smtplib
from typing import Any

import httpx

from mcp_server.config import Settings
from mcp_server.notifications.delivery import NotificationDeliveryService
from mcp_server.notifications.models import NotificationChannel, NotificationRequest


def _settings(**overrides: Any) -> Settings:
    values = {
        "pg_url": "postgresql+asyncpg://test:test@localhost:5432/test",
        "langfuse_enabled": False,
    }
    values.update(overrides)
    return Settings(**values)


async def test_telegram_send_message_success() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"ok": True, "result": {"message_id": 123}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        service = NotificationDeliveryService(
            _settings(
                notification_telegram_enabled=True,
                notification_telegram_bot_token="token",
                notification_telegram_api_base_url="https://telegram.test",
            ),
            http_client=client,
        )

        result = await service.send(NotificationRequest(
            channel=NotificationChannel.TELEGRAM_DM,
            target="123456",
            title="조건 충족",
            message="평균가가 5% 상승했습니다.",
        ))

    assert result.sent is True
    assert result.retryable is False
    assert result.provider == "telegram"
    assert result.provider_message_id == "123"
    assert len(requests) == 1
    assert requests[0].url == "https://telegram.test/bottoken/sendMessage"
    assert requests[0].headers["content-type"] == "application/json"
    assert json.loads(requests[0].read().decode()) == {
        "chat_id": "123456",
        "text": "조건 충족\n평균가가 5% 상승했습니다.",
    }


async def test_telegram_disabled_returns_structured_failure_without_http_call() -> None:
    called = False

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200, json={"ok": True})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        service = NotificationDeliveryService(_settings(), http_client=client)

        result = await service.send(NotificationRequest(
            channel=NotificationChannel.TELEGRAM_DM,
            target="123456",
            message="발송 테스트",
        ))

    assert called is False
    assert result.sent is False
    assert result.retryable is False
    assert result.provider == "telegram"
    assert "disabled" in result.error


async def test_telegram_429_is_retryable() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"ok": False, "description": "Too Many Requests"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        service = NotificationDeliveryService(
            _settings(
                notification_telegram_enabled=True,
                notification_telegram_bot_token="token",
            ),
            http_client=client,
        )

        result = await service.send(NotificationRequest(
            channel=NotificationChannel.TELEGRAM_DM,
            target="123456",
            message="발송 테스트",
        ))

    assert result.sent is False
    assert result.retryable is True
    assert result.status_code == 429


async def test_discord_dm_success_uses_two_provider_calls() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/users/@me/channels":
            return httpx.Response(200, json={"id": "channel-1"})
        if request.url.path == "/channels/channel-1/messages":
            return httpx.Response(200, json={"id": "message-1"})
        return httpx.Response(404)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        service = NotificationDeliveryService(
            _settings(
                notification_discord_enabled=True,
                notification_discord_bot_token="discord-token",
                notification_discord_api_base_url="https://discord.test",
            ),
            http_client=client,
        )

        result = await service.send(NotificationRequest(
            channel=NotificationChannel.DISCORD_DM,
            target="user-1",
            title="조건 충족",
            message="채용 공고가 새로 올라왔습니다.",
        ))

    assert result.sent is True
    assert result.provider == "discord"
    assert result.provider_message_id == "message-1"
    assert [request.url.path for request in requests] == [
        "/users/@me/channels",
        "/channels/channel-1/messages",
    ]
    assert requests[0].headers["authorization"] == "Bot discord-token"
    assert json.loads(requests[0].read().decode()) == {"recipient_id": "user-1"}
    assert json.loads(requests[1].read().decode()) == {
        "content": "조건 충족\n채용 공고가 새로 올라왔습니다.",
    }


async def test_discord_channel_creation_failure_stops_before_message_send() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(500, json={"message": "temporary failure"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        service = NotificationDeliveryService(
            _settings(
                notification_discord_enabled=True,
                notification_discord_bot_token="discord-token",
            ),
            http_client=client,
        )

        result = await service.send(NotificationRequest(
            channel=NotificationChannel.DISCORD_DM,
            target="user-1",
            message="발송 테스트",
        ))

    assert result.sent is False
    assert result.retryable is True
    assert result.status_code == 500
    assert len(requests) == 1


async def test_email_disabled_returns_structured_failure() -> None:
    service = NotificationDeliveryService(_settings())

    result = await service.send(NotificationRequest(
        channel=NotificationChannel.EMAIL,
        target="user@example.com",
        message="발송 테스트",
    ))

    assert result.sent is False
    assert result.retryable is False
    assert result.provider == "email"
    assert "disabled" in result.error


async def test_email_success_uses_smtp(monkeypatch) -> None:
    events: list[Any] = []

    class FakeSMTP:
        def __init__(self, host: str, port: int, timeout: float) -> None:
            events.append(("connect", host, port, timeout))

        def __enter__(self) -> FakeSMTP:
            events.append("enter")
            return self

        def __exit__(self, exc_type, exc, tb) -> None:
            events.append("exit")

        def starttls(self) -> None:
            events.append("starttls")

        def login(self, username: str, password: str) -> None:
            events.append(("login", username, password))

        def send_message(self, message) -> None:
            events.append(("send_message", message["From"], message["To"], message["Subject"]))

    monkeypatch.setattr(smtplib, "SMTP", FakeSMTP)

    service = NotificationDeliveryService(_settings(
        notification_email_enabled=True,
        notification_email_from="noreply@example.com",
        notification_email_host="smtp.example.com",
        notification_email_port=2525,
        notification_email_username="smtp-user",
        notification_email_password="smtp-pass",
    ))

    result = await service.send(NotificationRequest(
        channel=NotificationChannel.EMAIL,
        target="user@example.com",
        title="조건 충족",
        message="평균가가 5% 상승했습니다.",
    ))

    assert result.sent is True
    assert result.provider == "email"
    assert events == [
        ("connect", "smtp.example.com", 2525, 10.0),
        "enter",
        "starttls",
        ("login", "smtp-user", "smtp-pass"),
        ("send_message", "noreply@example.com", "user@example.com", "조건 충족"),
        "exit",
    ]
