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
                {"label": "평균 매매가", "value": "10억원 → 21억 6,170만원"},
                {"label": "변화율", "value": "+116.17%"},
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


def _recruitment_metadata() -> dict:
    return {
        "briefingContractVersion": "channel-v1",
        "briefing": {
            "domain": "채용",
            "title": "국토연구원 채용 새 공고",
            "summary": "국토연구원 공공채용 신규 공고가 1건 감지되었습니다.",
            "changes": [
                {"label": "신규 공고", "value": "1건"},
                {"label": "진행중 공고", "value": "1건"},
            ],
            "watchInfo": {
                "target": "국토연구원 채용 공고",
                "condition": "새 공고 1건 이상 증가",
                "keyword": "국토연구원",
                "dataSource": "공공채용",
            },
            "sources": [
                {
                    "label": "국토연구원 연구직 채용",
                    "url": "https://example.com/jobs/1",
                },
            ],
            "interpretation": "설정한 새 공고 조건을 충족했습니다.",
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

    assert "감시 정보" in captured["request"].message
    assert "핵심 변화" in captured["request"].message
    assert "확인할 점" in captured["request"].message
    assert "데이터 출처: 국토교통부 아파트 매매 실거래가" in captured["request"].message
    assert response["structured"]["sent"] is True
    assert response["metadata"]["briefing_contract_version"] == "channel-v1"
    assert response["metadata"]["briefing_rendered"] is True


async def test_send_notification_renders_recruitment_discord_briefing_metadata(monkeypatch) -> None:
    captured: dict[str, NotificationRequest] = {}

    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        captured["request"] = request
        return NotificationResult(
            sent=True,
            channel=request.channel,
            target=request.target,
            provider="discord",
            provider_message_id="123",
        )

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.DISCORD_DM,
        target="987654321012345678",
        subscription_id="sub-1",
        idempotency_key="sub-1:test",
        message="fallback",
        metadata=_recruitment_metadata(),
    ))

    assert captured["request"].title is None
    assert captured["request"].message == "\n".join([
        "**국토연구원 채용 새 공고**",
        "국토연구원 공공채용 신규 공고가 1건 감지되었습니다.",
        "",
        "**구독 정보**",
        "- 조건: 새 공고 1건 이상 증가",
        "- 데이터 출처: 공공채용",
        "",
        "**핵심 변화**",
        "- 신규 공고: 1건",
        "- 진행중 공고: 1건",
        "",
        "**채용 리스트**",
        "- 국토연구원 연구직 채용: <https://example.com/jobs/1>",
        "",
        "**확인할 점**",
        "- 설정한 새 공고 조건을 충족했습니다.",
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


async def test_send_notification_rejects_terse_real_estate_briefing_before_provider(monkeypatch) -> None:
    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        raise AssertionError("terse real-estate briefing must fail before provider delivery")

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.DISCORD_DM,
        target="987654321012345678",
        subscription_id="sub-1",
        message="fallback",
        metadata={
            "briefingContractVersion": "channel-v1",
            "briefing": {
                "domain": "real-estate",
                "title": "강남구 하락",
                "summary": "5% 하락",
                "changes": [{"label": "변화", "value": "하락"}],
                "watchInfo": {
                    "target": "강남구 아파트 매매가",
                    "region": "강남구",
                    "dealPeriod": "202403",
                },
                "sources": [{"label": "국토교통부 실거래가", "description": "공공 실거래 데이터"}],
                "interpretation": "확인",
            },
        },
    ))

    assert response["structured"]["sent"] is False
    assert response["structured"]["error"] == "briefing_contract_invalid"
    assert response["metadata"]["briefing_rendered"] is False
    assert "briefing is too terse" in response["metadata"]["briefing_error"]


async def test_send_notification_rejects_real_estate_briefing_without_core_change_metrics(monkeypatch) -> None:
    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        raise AssertionError("weak real-estate briefing must fail before provider delivery")

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.EMAIL,
        target="user@example.com",
        subscription_id="sub-1",
        message="fallback",
        metadata={
            "briefingContractVersion": "channel-v1",
            "briefing": {
                "domain": "real-estate",
                "title": "강남구 아파트 매매가격 변동 알림",
                "summary": "강남구 아파트 평균 매매가가 1만원에서 26억 5,926만원으로 26592500.0% 상승했습니다.",
                "changes": [
                    {"label": "구독 조건", "value": "1% 이상 상승"},
                    {"label": "조건 충족", "value": "상승 조건을 충족했습니다."},
                    {"label": "데이터 출처", "value": "국토교통부 실거래가"},
                ],
                "watchInfo": {
                    "target": "강남구 아파트 매매가",
                    "condition": "1% 이상 상승",
                    "region": "강남구",
                    "dealPeriod": "202603",
                    "dataSource": "국토교통부 실거래가",
                },
                "sources": [],
                "interpretation": "추가 데이터 확인이 필요합니다.",
            },
        },
    ))

    assert response["structured"]["sent"] is False
    assert response["structured"]["error"] == "briefing_contract_invalid"
    assert response["metadata"]["briefing_rendered"] is False
    assert "real-estate briefing requires average metric" in response["metadata"]["briefing_error"]


async def test_send_notification_rejects_terse_recruitment_briefing_before_provider(monkeypatch) -> None:
    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        raise AssertionError("terse recruitment briefing must fail before provider delivery")

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.TELEGRAM_DM,
        target="123456",
        subscription_id="sub-1",
        message="fallback",
        metadata={
            "briefingContractVersion": "channel-v1",
            "briefing": {
                "domain": "채용",
                "title": "새 공고",
                "summary": "1건",
                "changes": [{"label": "신규", "value": "1건"}],
                "watchInfo": {
                    "target": "간호사 채용",
                    "condition": "새 공고 1건 이상",
                    "keyword": "간호사",
                    "dataSource": "공공채용",
                },
                "sources": [{"label": "공고", "url": "https://example.com/jobs/1"}],
                "interpretation": "확인",
            },
        },
    ))

    assert response["structured"]["sent"] is False
    assert response["structured"]["error"] == "briefing_contract_invalid"
    assert response["metadata"]["briefing_rendered"] is False
    assert "briefing is too terse" in response["metadata"]["briefing_error"]


async def test_send_notification_rejects_subscription_execution_without_channel_v1_metadata(monkeypatch) -> None:
    async def fake_send(_, request: NotificationRequest) -> NotificationResult:
        raise AssertionError("subscription execution must fail before provider delivery without channel-v1 briefing")

    monkeypatch.setattr(notifications.NotificationDeliveryService, "send", fake_send)

    response = await notifications.send_notification(NotificationRequest(
        channel=NotificationChannel.TELEGRAM_DM,
        target="123456",
        subscription_id="sub-1",
        message="AI가 만든 자유 본문",
    ))

    assert response["structured"]["sent"] is False
    assert response["structured"]["error"] == "briefing_contract_required"
    assert response["metadata"]["briefing_rendered"] is False


async def test_send_notification_allows_backend_delivery_without_channel_v1_metadata(monkeypatch) -> None:
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
        message="백엔드가 조립한 기존 알림",
        metadata={
            "deliveryId": "delivery-1",
            "alertEventId": "alert-1",
        },
    ))

    assert captured["request"].message == "백엔드가 조립한 기존 알림"
    assert response["structured"]["sent"] is True
    assert response["metadata"]["briefing_rendered"] is False
