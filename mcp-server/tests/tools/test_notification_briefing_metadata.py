"""send_notification channel-v1 브리핑 metadata 렌더링 테스트."""

from __future__ import annotations

from mcp_server.notifications.models import NotificationChannel, NotificationRequest, NotificationResult
from mcp_server.tools import notifications


def _real_estate_metadata() -> dict:
    return {
        "briefingContractVersion": "channel-v1",
        "briefing": {
            "domain": "real-estate",
            "title": "강남구 아파트 매매가 상승 알림",
            "summary": "강남구 아파트 평균 매매가가 구독시점 10억원에서 최신 21억 6,170만원으로 116.17% 상승했습니다.",
            "changes": [
                {"label": "거래 건수", "value": "100건"},
                {"label": "최고가/최저가", "value": "75억원 / 1억 7,000만원"},
                {"label": "데이터 출처", "value": "국토교통부 아파트 매매 실거래가"},
            ],
            "watchInfo": {
                "target": "강남구 아파트 매매가",
                "region": "강남구",
                "dealPeriod": "202403",
            },
            "sources": [],
            "interpretation": "설정한 조건을 충족했습니다.",
        },
    }


async def test_send_notification_renders_real_estate_briefing_metadata(monkeypatch) -> None:
    captured: dict[str, NotificationRequest] = {}

    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        captured["request"] = request
        return NotificationResult(
            sent=True,
            channel=request.channel,
            target=request.target,
            provider="telegram",
            provider_message_id="123",
        )

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.TELEGRAM_DM,
        target="123456",
        subscription_id="sub-1",
        idempotency_key="sub-1:test",
        message="fallback",
        metadata=_real_estate_metadata(),
    ))

    assert captured["request"].message == "\n".join([
        "강남구 아파트 매매가 상승 알림",
        "",
        "강남구 아파트 평균 매매가가 구독시점 10억원에서 최신 21억 6,170만원으로 116.17% 상승했습니다.",
        "거래 건수: 100건",
        "최고가/최저가: 75억원 / 1억 7,000만원",
        "데이터 출처: 국토교통부 아파트 매매 실거래가",
    ])
    assert response["structured"]["sent"] is True
    assert response["metadata"]["briefing_contract_version"] == "channel-v1"
    assert response["metadata"]["briefing_rendered"] is True


async def test_send_notification_rejects_invalid_channel_v1_metadata(monkeypatch) -> None:
    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        raise AssertionError("invalid briefing metadata must fail before provider delivery")

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.TELEGRAM_DM,
        target="123456",
        subscription_id="sub-1",
        message="fallback",
        metadata={
            "briefingContractVersion": "channel-v1",
            "briefing": {
                "domain": "real-estate",
                "title": "강남구 아파트 매매가 상승 알림",
                "summary": "요약",
                "changes": [{"label": "거래 건수", "value": "100건"}],
                "watchInfo": {"target": "강남구 아파트 매매가"},
                "sources": [],
                "interpretation": "설정한 조건을 충족했습니다.",
            },
        },
    ))

    assert response["structured"]["sent"] is False
    assert response["structured"]["error"] == "briefing_contract_invalid"
    assert response["metadata"]["briefing_rendered"] is False
