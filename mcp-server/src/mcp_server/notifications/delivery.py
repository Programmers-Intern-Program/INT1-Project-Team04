"""MCP 알림 도구가 사용하는 외부 제공자 발송 구현.

원칙:
- Telegram / Discord 는 비동기 HTTP 호출로 발송한다.
- Email 은 smtplib 가 blocking API 이므로 asyncio.to_thread 로 격리한다.
- provider 비활성화, 설정 누락, 4xx 같은 정상 실패는 예외를 던지지 않고
  NotificationResult 로 반환한다.
- 429 / 5xx / 네트워크 오류는 retryable=True 로 표시해 호출자가 재시도 가능성을 알 수 있게 한다.
- token, password, Authorization header 는 결과나 로그에 포함하지 않는다.
"""

from __future__ import annotations

import asyncio
import smtplib
from email.message import EmailMessage
from typing import Any

import httpx

from mcp_server.config import Settings
from mcp_server.notifications.models import (
    NotificationChannel,
    NotificationRequest,
    NotificationResult,
)

_HTTP_TIMEOUT_SECONDS = 10.0


class NotificationDeliveryService:
    """설정된 외부 제공자로 알림을 발송한다."""

    def __init__(
        self,
        settings: Settings,
        *,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        self._settings = settings
        # 테스트에서는 MockTransport 를 가진 클라이언트를 주입해 실제 외부 호출을 막는다.
        self._http_client = http_client

    async def send(self, request: NotificationRequest) -> NotificationResult:
        """채널별 provider 발송 구현으로 위임한다."""
        if request.channel == NotificationChannel.TELEGRAM_DM:
            return await self._send_telegram(request)
        if request.channel == NotificationChannel.DISCORD_DM:
            return await self._send_discord(request)
        if request.channel == NotificationChannel.EMAIL:
            return await self._send_email(request)
        return NotificationResult(
            sent=False,
            channel=request.channel,
            target=request.target,
            provider="unknown",
            retryable=False,
            error=f"unsupported notification channel: {request.channel}",
        )

    async def _send_telegram(self, request: NotificationRequest) -> NotificationResult:
        """Telegram Bot API sendMessage 로 DM/채팅 알림을 발송한다."""
        provider = "telegram"
        token = self._settings.notification_telegram_bot_token
        if not self._settings.notification_telegram_enabled or not token:
            return self._failure(request, provider, "telegram notification provider is disabled")

        url = (
            f"{self._settings.notification_telegram_api_base_url.rstrip('/')}"
            f"/bot{token}/sendMessage"
        )
        try:
            response = await self._post_json(
                url,
                json={
                    "chat_id": request.target,
                    "text": _notification_text(request),
                },
            )
        except httpx.HTTPError as exc:
            return self._failure(request, provider, str(exc), retryable=True)

        payload = _response_json(response)
        if response.is_success and payload.get("ok") is True:
            message_id = payload.get("result", {}).get("message_id")
            return self._success(
                request,
                provider,
                provider_message_id=str(message_id) if message_id is not None else None,
                status_code=response.status_code,
            )

        return self._failure(
            request,
            provider,
            _provider_error(response, payload),
            retryable=_is_retryable_status(response.status_code),
            status_code=response.status_code,
        )

    async def _send_discord(self, request: NotificationRequest) -> NotificationResult:
        """Discord Bot API 로 DM 채널을 만든 뒤 메시지를 발송한다."""
        provider = "discord"
        token = self._settings.notification_discord_bot_token
        if not self._settings.notification_discord_enabled or not token:
            return self._failure(request, provider, "discord notification provider is disabled")

        base_url = self._settings.notification_discord_api_base_url.rstrip("/")
        headers = {
            "Authorization": f"Bot {token}",
            "Content-Type": "application/json",
        }
        try:
            channel_response = await self._post_json(
                f"{base_url}/users/@me/channels",
                headers=headers,
                json={"recipient_id": request.target},
            )
        except httpx.HTTPError as exc:
            return self._failure(request, provider, str(exc), retryable=True)

        channel_payload = _response_json(channel_response)
        if not channel_response.is_success:
            return self._failure(
                request,
                provider,
                _provider_error(channel_response, channel_payload),
                retryable=_is_retryable_status(channel_response.status_code),
                status_code=channel_response.status_code,
            )

        channel_id = channel_payload.get("id")
        if not channel_id:
            return self._failure(
                request,
                provider,
                "discord create DM response did not include channel id",
                retryable=False,
                status_code=channel_response.status_code,
            )

        try:
            message_response = await self._post_json(
                f"{base_url}/channels/{channel_id}/messages",
                headers=headers,
                json={"content": _notification_text(request)},
            )
        except httpx.HTTPError as exc:
            return self._failure(request, provider, str(exc), retryable=True)

        message_payload = _response_json(message_response)
        if message_response.is_success:
            message_id = message_payload.get("id")
            return self._success(
                request,
                provider,
                provider_message_id=str(message_id) if message_id is not None else None,
                status_code=message_response.status_code,
            )

        return self._failure(
            request,
            provider,
            _provider_error(message_response, message_payload),
            retryable=_is_retryable_status(message_response.status_code),
            status_code=message_response.status_code,
        )

    async def _send_email(self, request: NotificationRequest) -> NotificationResult:
        """SMTP 설정을 사용해 Email 알림을 발송한다."""
        provider = "email"
        if (
            not self._settings.notification_email_enabled
            or not self._settings.notification_email_host
            or not self._settings.notification_email_from
        ):
            return self._failure(request, provider, "email notification provider is disabled")

        try:
            await asyncio.to_thread(self._send_email_sync, request)
        except smtplib.SMTPRecipientsRefused as exc:
            return self._failure(request, provider, str(exc), retryable=False)
        except smtplib.SMTPException as exc:
            return self._failure(request, provider, str(exc), retryable=True)
        except OSError as exc:
            return self._failure(request, provider, str(exc), retryable=True)

        return self._success(request, provider)

    def _send_email_sync(self, request: NotificationRequest) -> None:
        """smtplib 기반 blocking 발송 본문. 호출부에서 별도 thread 로 실행한다."""
        message = EmailMessage()
        message["From"] = self._settings.notification_email_from or ""
        message["To"] = request.target
        message["Subject"] = request.title or "구독 조건이 충족되었습니다"
        message.set_content(request.message)

        with smtplib.SMTP(
            self._settings.notification_email_host or "",
            self._settings.notification_email_port,
            timeout=_HTTP_TIMEOUT_SECONDS,
        ) as smtp:
            if self._settings.notification_email_starttls_enable:
                smtp.starttls()
            if (
                self._settings.notification_email_smtp_auth
                and self._settings.notification_email_username
                and self._settings.notification_email_password
            ):
                smtp.login(
                    self._settings.notification_email_username,
                    self._settings.notification_email_password,
                )
            smtp.send_message(message)

    async def _post_json(
        self,
        url: str,
        *,
        json: dict[str, Any],
        headers: dict[str, str] | None = None,
    ) -> httpx.Response:
        if self._http_client is not None:
            return await self._http_client.post(url, json=json, headers=headers)
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            return await client.post(url, json=json, headers=headers)

    def _success(
        self,
        request: NotificationRequest,
        provider: str,
        *,
        provider_message_id: str | None = None,
        status_code: int | None = None,
    ) -> NotificationResult:
        return NotificationResult(
            sent=True,
            channel=request.channel,
            target=request.target,
            provider=provider,
            provider_message_id=provider_message_id,
            retryable=False,
            status_code=status_code,
            error=None,
        )

    def _failure(
        self,
        request: NotificationRequest,
        provider: str,
        error: str,
        *,
        retryable: bool = False,
        status_code: int | None = None,
    ) -> NotificationResult:
        return NotificationResult(
            sent=False,
            channel=request.channel,
            target=request.target,
            provider=provider,
            provider_message_id=None,
            retryable=retryable,
            status_code=status_code,
            error=error,
        )


def _notification_text(request: NotificationRequest) -> str:
    """제목이 있으면 제목과 본문을 줄바꿈으로 합쳐 provider 메시지를 만든다."""
    if request.title:
        return f"{request.title}\n{request.message}"
    return request.message


def _response_json(response: httpx.Response) -> dict[str, Any]:
    """provider 응답이 JSON 객체가 아니면 빈 dict 로 취급한다."""
    try:
        value = response.json()
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


def _provider_error(response: httpx.Response, payload: dict[str, Any]) -> str:
    """provider 별 오류 필드명을 공통 문자열로 정규화한다."""
    for key in ("description", "message", "error"):
        value = payload.get(key)
        if value:
            return str(value)
    return response.text or f"provider returned HTTP {response.status_code}"


def _is_retryable_status(status_code: int) -> bool:
    """rate limit 또는 서버 오류는 재시도 가능 실패로 본다."""
    return status_code == 429 or status_code >= 500
