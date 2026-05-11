"""부동산/채용 channel-v1 브리핑의 채널별 golden renderer 테스트."""

from __future__ import annotations

from mcp_server.notifications.briefing_models import NotificationBriefing
from mcp_server.notifications.briefing_renderers import render_for_channel
from mcp_server.notifications.models import NotificationChannel


def _real_estate_briefing() -> NotificationBriefing:
    return NotificationBriefing.model_validate({
        "domain": "부동산",
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
            "condition": "10% 이상 상승",
            "region": "강남구",
            "dealPeriod": "202403",
        },
        "sources": [],
        "interpretation": "설정한 조건을 충족했습니다.",
    })


def _recruitment_briefing() -> NotificationBriefing:
    return NotificationBriefing.model_validate({
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
    })


def test_real_estate_telegram_golden_message() -> None:
    rendered = render_for_channel(_real_estate_briefing(), NotificationChannel.TELEGRAM_DM)

    assert rendered.provider_title is None
    assert "핵심 변화" in rendered.message
    assert "확인할 점" in rendered.message
    assert "데이터 출처: 국토교통부 아파트 매매 실거래가" in rendered.message
    assert rendered.message.index("핵심 변화") < rendered.message.index("확인할 점")
    assert rendered.message.index("확인할 점") < rendered.message.index("데이터 출처")


def test_real_estate_discord_golden_message() -> None:
    rendered = render_for_channel(_real_estate_briefing(), NotificationChannel.DISCORD_DM)

    assert rendered.provider_title is None
    assert rendered.message == "\n".join([
        "**강남구 아파트 매매가 상승 알림**",
        "강남구 아파트 평균 매매가가 구독시점 10억원에서 최신 21억 6,170만원으로 116.17% 상승했습니다.",
        "",
        "**감시 정보**",
        "- 지역: 강남구",
        "- 조회 기간: 202403",
        "- 조건: 10% 이상 상승",
        "",
        "**핵심 변화**",
        "- 평균 매매가: 10억원 → 21억 6,170만원",
        "- 변화율: +116.17%",
        "- 거래 건수: 100건",
        "- 최고가/최저가: 75억원 / 1억 7,000만원",
        "",
        "**확인할 점**",
        "- 설정한 조건을 충족했습니다.",
        "",
        "데이터 출처: 국토교통부 아파트 매매 실거래가",
    ])


def test_real_estate_email_golden_html() -> None:
    rendered = render_for_channel(_real_estate_briefing(), NotificationChannel.EMAIL)

    assert rendered.provider_title == "강남구 아파트 매매가 상승 알림"
    assert "부동산 변화 브리핑" in rendered.message
    assert "감시 정보" in rendered.message
    assert "핵심 변화" in rendered.message
    assert "확인할 점" in rendered.message
    assert "데이터 출처: 국토교통부 아파트 매매 실거래가" in rendered.message
    assert "평균 매매가" in rendered.message


def test_real_estate_briefing_accepts_tool_period_alias_and_sample_count_label() -> None:
    payload = _real_estate_briefing().model_dump(mode="json", by_alias=True)
    payload["changes"] = [
        {"label": "평균 매매가", "value": "27억원 → 27억 4,924만원"},
        {"label": "변화율", "value": "+1.82%"},
        {"label": "표본 수", "value": "100건"},
        {"label": "데이터 출처", "value": "국토교통부 아파트 매매 실거래가"},
    ]
    payload["watchInfo"].pop("dealPeriod")
    payload["watchInfo"]["deal_ymd"] = "202604"

    briefing = NotificationBriefing.model_validate(payload)
    rendered = render_for_channel(briefing, NotificationChannel.DISCORD_DM)

    assert "- 조회 기간: 202604" in rendered.message
    assert "- 표본 수: 100건" in rendered.message


def test_real_estate_rent_briefing_accepts_natural_ai_labels() -> None:
    payload = _real_estate_briefing().model_dump(mode="json", by_alias=True)
    payload["title"] = "강남구 오피스텔 전월세 보증금 상승"
    payload["summary"] = "강남구 오피스텔 평균 전월세가가 기준보다 2.31% 상승했습니다."
    payload["changes"] = [
        {"label": "평균 전월세가", "value": "1억 3,400만원 → 1억 3,709만원"},
        {"label": "증감률", "value": "+2.31%"},
        {"label": "데이터 건수", "value": "100건"},
        {"label": "데이터 출처", "value": "국토교통부 오피스텔 전월세 실거래가"},
    ]
    payload["watchInfo"].pop("dealPeriod")
    payload["watchInfo"]["dealYm"] = "202604"

    briefing = NotificationBriefing.model_validate(payload)
    rendered = render_for_channel(briefing, NotificationChannel.EMAIL)

    assert "강남구 오피스텔 전월세 보증금 상승" in rendered.message
    assert "데이터 건수" in rendered.message


def test_real_estate_briefing_accepts_ai_payload_without_watch_target_and_numeric_diffs() -> None:
    payload = _real_estate_briefing().model_dump(mode="json", by_alias=True)
    payload["title"] = "강남구 오피스텔 전월세 보증금 2.31% 상승"
    payload["summary"] = "강남구 오피스텔 평균 보증금이 1억 3,400만원에서 1억 3,709만원으로 2.31% 상승했습니다."
    payload["changes"] = [
        {
            "label": "평균 보증금",
            "value": "1억 3,400만원 → 1억 3,709만원 (2.31%)",
            "previous": 13400.0,
            "current": 13709.0,
        },
        {"label": "변화율", "value": "2.31%"},
        {"label": "거래건수", "value": "100건"},
        {"label": "데이터 출처", "value": "국토교통부 실거래가"},
    ]
    payload["watchInfo"] = {
        "dealPeriod": "202604",
        "dataSource": "국토교통부 실거래가",
        "region": "강남구",
        "condition": "강남구 오피스텔 전월세 보증금 1% 이상 상승하면 텔레그램으로 알려줘",
    }
    payload["interpretation"] = "거래 건수와 함께 변동 폭을 고려해 추가적인 시장 확인이 필요합니다."

    briefing = NotificationBriefing.model_validate(payload)
    rendered = render_for_channel(briefing, NotificationChannel.TELEGRAM_DM)

    assert "강남구 오피스텔 전월세 보증금 2.31% 상승" in rendered.message
    assert "평균 보증금: 1억 3,400만원 → 1억 3,709만원 (2.31%)" in rendered.message


def test_recruitment_telegram_golden_message() -> None:
    rendered = render_for_channel(_recruitment_briefing(), NotificationChannel.TELEGRAM_DM)

    assert rendered.provider_title is None
    assert rendered.message == "\n".join([
        "국토연구원 채용 새 공고",
        "",
        "국토연구원 공공채용 신규 공고가 1건 감지되었습니다.",
        "구독 조건: 새 공고 1건 이상 증가",
        "데이터 출처: 공공채용",
        "",
        "핵심 변화",
        "1. 신규 공고: 1건",
        "2. 진행중 공고: 1건",
        "",
        "채용 리스트",
        "- 국토연구원 연구직 채용: https://example.com/jobs/1",
        "",
        "확인할 점",
        "- 설정한 새 공고 조건을 충족했습니다.",
    ])


def test_recruitment_discord_golden_message() -> None:
    rendered = render_for_channel(_recruitment_briefing(), NotificationChannel.DISCORD_DM)

    assert rendered.provider_title is None
    assert "**채용 리스트**" in rendered.message
    assert "- 국토연구원 연구직 채용: <https://example.com/jobs/1>" in rendered.message
    assert "**확인할 점**" in rendered.message
    assert rendered.message.index("**핵심 변화**") < rendered.message.index("**채용 리스트**")
    assert rendered.message.index("**채용 리스트**") < rendered.message.index("**확인할 점**")


def test_recruitment_email_golden_html() -> None:
    rendered = render_for_channel(_recruitment_briefing(), NotificationChannel.EMAIL)

    assert rendered.provider_title == "국토연구원 채용 새 공고"
    assert "채용 변화 브리핑" in rendered.message
    assert "구독 정보" in rendered.message
    assert "채용 리스트" in rendered.message
    assert "국토연구원 연구직 채용" in rendered.message
    assert "확인할 점" in rendered.message
    assert "https://example.com/jobs/1" in rendered.message
    assert ">https://example.com/jobs/1<" in rendered.message


def test_recruitment_email_accepts_www_source_url() -> None:
    payload = _recruitment_briefing().model_dump(mode="json", by_alias=True)
    payload["sources"] = [
        {
            "label": "대한적십자사 인천사할린동포복지회관 직원 채용 공고",
            "url": "www.redcross.or.kr/redrecruit",
        }
    ]
    briefing = NotificationBriefing.model_validate(payload)

    rendered = render_for_channel(briefing, NotificationChannel.EMAIL)

    assert "대한적십자사 인천사할린동포복지회관 직원 채용 공고" in rendered.message
    assert "https://www.redcross.or.kr/redrecruit" in rendered.message
