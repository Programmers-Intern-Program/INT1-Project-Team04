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
        "notification_telegram_enabled": False,
        "notification_telegram_bot_token": None,
        "notification_discord_enabled": False,
        "notification_discord_bot_token": None,
        "notification_email_enabled": False,
        "notification_email_from": None,
        "notification_email_host": None,
        "notification_email_username": None,
        "notification_email_password": None,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_test_settings_do_not_read_local_notification_env() -> None:
    settings = _settings()

    assert settings.notification_telegram_enabled is False
    assert settings.notification_telegram_bot_token is None
    assert settings.notification_discord_enabled is False
    assert settings.notification_discord_bot_token is None
    assert settings.notification_email_enabled is False
    assert settings.notification_email_from is None
    assert settings.notification_email_host is None
    assert settings.notification_email_username is None
    assert settings.notification_email_password is None


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


async def test_email_html_message_is_sent_as_html_alternative(monkeypatch) -> None:
    sent_messages: list[Any] = []

    class FakeSMTP:
        def __init__(self, host: str, port: int, timeout: float) -> None:
            pass

        def __enter__(self) -> FakeSMTP:
            return self

        def __exit__(self, exc_type, exc, tb) -> None:
            pass

        def starttls(self) -> None:
            pass

        def login(self, username: str, password: str) -> None:
            pass

        def send_message(self, message) -> None:
            sent_messages.append(message)

    monkeypatch.setattr(smtplib, "SMTP", FakeSMTP)

    html = "<!doctype html><html lang=\"ko\"><body><h1>강동구 아파트 평균 매매가 1억 감소</h1></body></html>"
    service = NotificationDeliveryService(_settings(
        notification_email_enabled=True,
        notification_email_from="noreply@example.com",
        notification_email_host="smtp.example.com",
    ))

    result = await service.send(NotificationRequest(
        channel=NotificationChannel.EMAIL,
        target="user@example.com",
        title="강동구 아파트 평균 매매가 1억 감소",
        message=html,
    ))

    assert result.sent is True
    assert len(sent_messages) == 1
    message = sent_messages[0]
    assert message.is_multipart()
    assert message.get_body(preferencelist=("html",)).get_content_type() == "text/html"
    assert "<h1>강동구 아파트 평균 매매가 1억 감소</h1>" in message.get_body(
        preferencelist=("html",)
    ).get_content()
    assert "<!doctype html>" not in message.get_body(preferencelist=("plain",)).get_content()


async def test_email_plain_text_change_notification_uses_html_template(monkeypatch) -> None:
    sent_messages: list[Any] = []

    class FakeSMTP:
        def __init__(self, host: str, port: int, timeout: float) -> None:
            pass

        def __enter__(self) -> FakeSMTP:
            return self

        def __exit__(self, exc_type, exc, tb) -> None:
            pass

        def starttls(self) -> None:
            pass

        def login(self, username: str, password: str) -> None:
            pass

        def send_message(self, message) -> None:
            sent_messages.append(message)

    monkeypatch.setattr(smtplib, "SMTP", FakeSMTP)

    service = NotificationDeliveryService(_settings(
        notification_email_enabled=True,
        notification_email_from="noreply@example.com",
        notification_email_host="smtp.example.com",
    ))

    result = await service.send(NotificationRequest(
        channel=NotificationChannel.EMAIL,
        target="user@example.com",
        title="강남구 아파트 매매가 5% 이상 상승 조건 충족",
        message="강남구 아파트 매매 평균 가격이 170.97% 상승했습니다.\n기준값: 10억원\n현재값: 27억969만원",
    ))

    assert result.sent is True
    assert len(sent_messages) == 1
    message = sent_messages[0]
    assert message.is_multipart()

    html_body = message.get_body(preferencelist=("html",)).get_content()
    assert "<!doctype html>" in html_body
    assert "AI 변화 브리핑" in html_body
    assert "강남구 아파트 매매가 5% 이상 상승 조건 충족" in html_body
    assert "기준값: 10억원" in html_body
    assert "현재값: 27억969만원" in html_body

    plain_body = message.get_body(preferencelist=("plain",)).get_content()
    assert "강남구 아파트 매매 평균 가격이 170.97% 상승했습니다." in plain_body
    assert "<!doctype html>" not in plain_body


async def test_email_recruitment_change_notification_uses_job_template(monkeypatch) -> None:
    sent_messages: list[Any] = []

    class FakeSMTP:
        def __init__(self, host: str, port: int, timeout: float) -> None:
            pass

        def __enter__(self) -> FakeSMTP:
            return self

        def __exit__(self, exc_type, exc, tb) -> None:
            pass

        def starttls(self) -> None:
            pass

        def login(self, username: str, password: str) -> None:
            pass

        def send_message(self, message) -> None:
            sent_messages.append(message)

    monkeypatch.setattr(smtplib, "SMTP", FakeSMTP)

    service = NotificationDeliveryService(_settings(
        notification_email_enabled=True,
        notification_email_from="noreply@example.com",
        notification_email_host="smtp.example.com",
    ))

    result = await service.send(NotificationRequest(
        channel=NotificationChannel.EMAIL,
        target="user@example.com",
        title="공공기관 간호사 채용 새 공고",
        message=(
            "새로운 간호사 채용 공고가 2건 올라왔습니다!\n\n"
            "**공공채용**\n"
            "- 제주대학교병원 사업인력 계약직 간호사 모집공고: "
            "https://www.jejunuh.co.kr/news/recruit/_/22869/view.do\n"
            "- 대한적십자사 인천사할린동포복지회관 직원(간호사) 채용 공고: "
            "www.redcross.or.kr/redrecruit"
        ),
    ))

    assert result.sent is True
    assert len(sent_messages) == 1
    message = sent_messages[0]
    assert message.is_multipart()

    html_body = message.get_body(preferencelist=("html",)).get_content()
    assert "채용 변화 브리핑" in html_body
    assert "AI 변화 브리핑" not in html_body
    assert "공공채용" in html_body
    assert "**공공채용**" not in html_body
    assert "제주대학교병원 사업인력 계약직 간호사 모집공고" in html_body
    assert "대한적십자사 인천사할린동포복지회관 직원(간호사) 채용 공고" in html_body
    assert "href=\"https://www.jejunuh.co.kr/news/recruit/_/22869/view.do\"" in html_body
    assert "href=\"https://www.redcross.or.kr/redrecruit\"" in html_body
