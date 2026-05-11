"""표준 브리핑 계약을 Discord, Telegram, Email 본문으로 결정적으로 렌더링한다."""

from __future__ import annotations

from dataclasses import dataclass
from html import escape

from mcp_server.notifications.briefing_models import NotificationBriefing
from mcp_server.notifications.models import NotificationChannel


@dataclass(frozen=True)
class RenderedNotification:
    """provider 발송 직전에 사용할 채널별 제목과 본문."""

    message: str
    provider_title: str | None


def render_for_channel(briefing: NotificationBriefing, channel: NotificationChannel) -> RenderedNotification:
    """AI 자유 본문 대신 검증된 브리핑 모델로 채널별 최종 본문을 만든다."""
    if _is_real_estate(briefing):
        return _render_real_estate(briefing, channel)
    if _is_recruitment(briefing):
        return _render_recruitment(briefing, channel)
    if channel == NotificationChannel.DISCORD_DM:
        return RenderedNotification(_generic_discord_text(briefing), None)
    if channel == NotificationChannel.EMAIL:
        return RenderedNotification(_generic_email_html(briefing), briefing.title)
    return RenderedNotification(_generic_plain_text(briefing), None)


def _render_real_estate(briefing: NotificationBriefing, channel: NotificationChannel) -> RenderedNotification:
    """부동산 브리핑은 숫자 지표와 데이터 출처를 채널별 고정 양식으로 노출한다."""
    if channel == NotificationChannel.DISCORD_DM:
        return RenderedNotification(_real_estate_discord_text(briefing), None)
    if channel == NotificationChannel.EMAIL:
        return RenderedNotification(_real_estate_email_html(briefing), briefing.title)
    return RenderedNotification(_real_estate_plain_text(briefing), None)


def _real_estate_plain_text(briefing: NotificationBriefing) -> str:
    """Telegram용 부동산 본문은 감시 정보, 변화, 확인할 점을 읽는 순서대로 노출한다."""
    lines = [briefing.title, "", briefing.summary]
    watch_lines = _real_estate_watch_info_plain_lines(briefing)
    if watch_lines:
        lines.extend(["", "감시 정보", *watch_lines])
    pairs = _real_estate_change_pairs(briefing)
    if pairs:
        lines.extend(["", "핵심 변화"])
        lines.extend(f"- {label}: {value}" for label, value in pairs)
    if briefing.interpretation:
        lines.extend(["", "확인할 점", f"- {briefing.interpretation}"])
    data_source = _real_estate_data_source(briefing)
    if data_source:
        lines.extend(["", f"데이터 출처: {data_source}"])
    return "\n".join(lines).strip()


def _real_estate_discord_text(briefing: NotificationBriefing) -> str:
    """Discord용 부동산 본문은 Markdown 섹션으로 숫자 지표를 스캔하기 쉽게 만든다."""
    lines = [f"**{briefing.title}**", briefing.summary]
    watch_lines = _real_estate_watch_info_markdown_lines(briefing)
    if watch_lines:
        lines.extend(["", "**감시 정보**", *watch_lines])
    pairs = _real_estate_change_pairs(briefing)
    if pairs:
        lines.extend(["", "**핵심 변화**"])
        lines.extend(f"- {label}: {value}" for label, value in pairs)
    if briefing.interpretation:
        lines.extend(["", "**확인할 점**", f"- {briefing.interpretation}"])
    data_source = _real_estate_data_source(briefing)
    if data_source:
        lines.extend(["", f"데이터 출처: {data_source}"])
    return "\n".join(lines).strip()


def _real_estate_email_html(briefing: NotificationBriefing) -> str:
    """부동산 Email은 감시 기준 카드와 핵심 변화 테이블을 서버에서 확정 렌더링한다."""
    rows = _table_rows(_real_estate_change_pairs(briefing))
    watch_info_card = _real_estate_watch_info_card(briefing)
    data_source = _real_estate_data_source(briefing)
    source_line = (
        f'      <p style="margin:12px 0 0;color:#475569;font-size:12px;line-height:1.5;font-weight:800;">데이터 출처: {escape(data_source)}</p>\n'
        if data_source else ""
    )
    return f"""<!doctype html>
<html lang="ko">
<body style="margin:0;background:#f4f7fb;color:#0f172a;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
  <div style="max-width:560px;margin:0 auto;padding:20px 14px;">
    <div style="background:#ffffff;border:1px solid #dbe4f0;border-radius:16px;padding:22px;">
      <span style="display:inline-block;background:#2563eb;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;font-weight:800;">부동산 변화 브리핑</span>
      <h1 style="margin:14px 0 10px;font-size:22px;line-height:1.35;color:#0f172a;font-weight:900;">{escape(briefing.title)}</h1>
      <p style="margin:0 0 16px;color:#334155;font-size:14px;line-height:1.65;font-weight:700;">{escape(briefing.summary)}</p>
{watch_info_card}
      <p style="margin:0 0 8px;color:#1d4ed8;font-size:13px;line-height:1.2;font-weight:900;">핵심 변화</p>
      <table role="presentation" width="100%" style="border-collapse:collapse;margin:0;">{rows}</table>
      <p style="margin:14px 0 6px;color:#1d4ed8;font-size:13px;line-height:1.2;font-weight:900;">확인할 점</p>
      <p style="margin:0;color:#334155;font-size:13px;line-height:1.6;font-weight:700;">{escape(briefing.interpretation)}</p>
{source_line.rstrip()}
    </div>
  </div>
</body>
</html>"""


def _render_recruitment(briefing: NotificationBriefing, channel: NotificationChannel) -> RenderedNotification:
    """채용 브리핑은 신규 공고 링크가 채널별 최종 본문에 남도록 전용 양식으로 렌더링한다."""
    if channel == NotificationChannel.DISCORD_DM:
        return RenderedNotification(_recruitment_discord_text(briefing), None)
    if channel == NotificationChannel.EMAIL:
        return RenderedNotification(_recruitment_email_html(briefing), briefing.title)
    return RenderedNotification(_recruitment_plain_text(briefing), None)


def _generic_plain_text(briefing: NotificationBriefing) -> str:
    lines = [briefing.title, "", briefing.summary, "", "핵심 변화"]
    lines.extend(f"{index}. {change.label}: {change.value}" for index, change in enumerate(briefing.changes, start=1))
    if briefing.sources:
        lines.extend(["", "근거 링크"])
        lines.extend(_source_plain_lines(briefing))
    return "\n".join(lines).strip()


def _generic_discord_text(briefing: NotificationBriefing) -> str:
    lines = [f"**{briefing.title}**", briefing.summary, "", "**핵심 변화**"]
    lines.extend(f"- {change.label}: {change.value}" for change in briefing.changes)
    if briefing.sources:
        lines.extend(["", "**근거 링크**"])
        lines.extend(_source_markdown_lines(briefing))
    return "\n".join(lines).strip()


def _recruitment_plain_text(briefing: NotificationBriefing) -> str:
    """Telegram용 채용 본문은 공고명과 URL을 같은 줄에 두어 모바일에서 복사하기 쉽게 한다."""
    lines = [briefing.title, "", briefing.summary]
    lines.extend(_recruitment_info_plain_lines(briefing))
    lines.extend(["", "핵심 변화"])
    lines.extend(
        f"{index}. {change.label}: {change.value}"
        for index, change in enumerate(briefing.changes, start=1)
    )
    if briefing.sources:
        lines.extend(["", "채용 리스트"])
        lines.extend(_source_bullet_plain_lines(briefing))
    lines.extend(["", "확인할 점", f"- {briefing.interpretation}"])
    return "\n".join(lines).strip()


def _recruitment_discord_text(briefing: NotificationBriefing) -> str:
    """Discord용 채용 본문은 채용 리스트 링크를 Markdown 링크 보호 형식으로 고정한다."""
    lines = [f"**{briefing.title}**", briefing.summary, "", "**핵심 변화**"]
    info_lines = _recruitment_info_markdown_lines(briefing)
    if info_lines:
        lines = [f"**{briefing.title}**", briefing.summary, "", "**구독 정보**", *info_lines, "", "**핵심 변화**"]
    lines.extend(f"- {change.label}: {change.value}" for change in briefing.changes)
    if briefing.sources:
        lines.extend(["", "**채용 리스트**"])
        lines.extend(_source_markdown_lines(briefing))
    lines.extend(["", "**확인할 점**", f"- {briefing.interpretation}"])
    return "\n".join(lines).strip()


def _recruitment_email_html(briefing: NotificationBriefing) -> str:
    """채용 Email은 신규 공고 링크를 버튼으로 고정해 plain text 파싱에 의존하지 않는다."""
    rows = _table_rows([(change.label, change.value) for change in briefing.changes])
    info_card = _recruitment_info_card(briefing)
    source_cards = _recruitment_email_source_cards(briefing)
    return f"""<!doctype html>
<html lang="ko">
<body style="margin:0;background:#f4f7fb;color:#0f172a;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
  <div style="max-width:560px;margin:0 auto;padding:20px 14px;">
    <div style="background:#ffffff;border:1px solid #dbe4f0;border-radius:16px;padding:22px;">
      <span style="display:inline-block;background:#2563eb;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;font-weight:800;">채용 변화 브리핑</span>
      <h1 style="margin:14px 0 10px;font-size:22px;line-height:1.35;color:#0f172a;font-weight:900;">{escape(briefing.title)}</h1>
      <p style="margin:0 0 16px;color:#334155;font-size:14px;line-height:1.65;font-weight:700;">{escape(briefing.summary)}</p>
{info_card}
      <p style="margin:0 0 8px;color:#1d4ed8;font-size:13px;line-height:1.2;font-weight:900;">핵심 변화</p>
      <table role="presentation" width="100%" style="border-collapse:collapse;margin:0 0 14px;">{rows}</table>
      <div style="margin-top:14px;">
        <p style="margin:0 0 8px;color:#1d4ed8;font-size:13px;line-height:1.2;font-weight:900;">채용 리스트</p>
{source_cards}
      </div>
      <p style="margin:14px 0 6px;color:#1d4ed8;font-size:13px;line-height:1.2;font-weight:900;">확인할 점</p>
      <p style="margin:0;color:#334155;font-size:13px;line-height:1.6;font-weight:700;">{escape(briefing.interpretation)}</p>
    </div>
  </div>
</body>
</html>"""


def _generic_email_html(briefing: NotificationBriefing) -> str:
    change_rows = "".join(
        f"<tr><th>{escape(change.label)}</th><td>{escape(change.value)}</td></tr>"
        for change in briefing.changes
    )
    source_buttons = "".join(
        f'<a href="{escape(source.url)}" style="display:inline-block;margin:6px 6px 0 0;padding:10px 12px;background:#2563eb;color:#fff;text-decoration:none;border-radius:8px;font-weight:800;">공고 보기</a>'
        for source in briefing.sources
        if source.url
    )
    return f"""<!doctype html>
<html lang="ko">
<body style="margin:0;background:#f4f7fb;color:#0f172a;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
  <div style="max-width:560px;margin:0 auto;padding:20px 14px;">
    <div style="background:#ffffff;border:1px solid #dbe4f0;border-radius:16px;padding:22px;">
      <h1 style="margin:0 0 10px;font-size:22px;line-height:1.35;color:#0f172a;font-weight:900;">{escape(briefing.title)}</h1>
      <p style="margin:0 0 16px;color:#334155;font-size:14px;line-height:1.65;font-weight:700;">{escape(briefing.summary)}</p>
      <table role="presentation" width="100%" style="border-collapse:collapse;margin:0 0 14px;">{change_rows}</table>
      <div>{source_buttons}</div>
    </div>
  </div>
</body>
</html>"""


def _real_estate_pairs(briefing: NotificationBriefing) -> list[tuple[str, str]]:
    """AI가 넘긴 부동산 changes를 renderer가 쓰기 쉬운 label/value 쌍으로 정규화한다."""
    return [
        (change.label, change.value)
        for change in briefing.changes
        if change.label and change.value
    ]


def _real_estate_change_pairs(briefing: NotificationBriefing) -> list[tuple[str, str]]:
    """데이터 출처는 별도 줄에 노출하므로 핵심 변화 테이블에서는 제외한다."""
    return [
        (label, value)
        for label, value in _real_estate_pairs(briefing)
        if "출처" not in label
    ]


def _real_estate_data_source(briefing: NotificationBriefing) -> str | None:
    """부동산 데이터 출처는 watchInfo, changes, sources 순서로 가장 명확한 값을 고른다."""
    if briefing.watch_info.data_source:
        return briefing.watch_info.data_source
    for label, value in _real_estate_pairs(briefing):
        if "출처" in label:
            return value
    for source in briefing.sources:
        if source.label:
            return source.label
        if source.description:
            return source.description
    return None


def _table_rows(pairs: list[tuple[str, str]]) -> str:
    """Email 카드에서 공통으로 쓰는 label/value 지표 테이블 row를 만든다."""
    return "".join(
        f'<tr><th style="text-align:left;padding:10px 0;border-top:1px solid #e2e8f0;color:#475569;font-size:13px;">{escape(label)}</th>'
        f'<td style="text-align:right;padding:10px 0;border-top:1px solid #e2e8f0;color:#0f172a;font-size:13px;font-weight:800;">{escape(value)}</td></tr>'
        for label, value in pairs
    )


def _recruitment_email_source_cards(briefing: NotificationBriefing) -> str:
    """Email 본문에서 공고 제목과 URL이 둘 다 눈에 보이도록 렌더링한다."""
    cards: list[str] = []
    for source in briefing.sources:
        if not source.url:
            continue
        safe_label = escape(source.label)
        safe_url = escape(source.url)
        cards.append(
            "\n".join([
                '        <div style="border:1px solid #dbe4f0;border-radius:10px;padding:11px 12px;margin:0 0 8px;background:#ffffff;">',
                f'          <a href="{safe_url}" style="display:block;color:#1d4ed8;text-decoration:none;font-size:13px;line-height:1.45;font-weight:900;">{safe_label}</a>',
                f'          <p style="margin:6px 0 0;color:#475569;font-size:12px;line-height:1.45;font-weight:700;word-break:break-all;">{safe_url}</p>',
                "        </div>",
            ])
        )
    return "\n".join(cards)


def _single_value_card(label: str, value: str | None) -> str:
    """Email에서 단일 강조 값을 보여줄 때 쓰는 작은 정보 카드."""
    if not value:
        return ""
    return "\n".join([
        '      <div style="background:#eff6ff;border:1px solid #bfdbfe;border-radius:12px;padding:12px;margin:0 0 14px;">',
        f'        <p style="margin:0 0 6px;color:#1d4ed8;font-size:12px;line-height:1.2;font-weight:900;">{escape(label)}</p>',
        f'        <p style="margin:0;color:#0f172a;font-size:14px;line-height:1.45;font-weight:800;">{escape(value)}</p>',
        "      </div>",
    ])


def _real_estate_watch_info_card(briefing: NotificationBriefing) -> str:
    """부동산 Email 상단에 사용자가 설정한 감시 기준을 고정 위치로 보여준다."""
    lines = [
        '      <div style="background:#eff6ff;border:1px solid #bfdbfe;border-radius:12px;padding:12px;margin:0 0 14px;">',
        '        <p style="margin:0 0 8px;color:#1d4ed8;font-size:12px;line-height:1.2;font-weight:900;">감시 정보</p>',
    ]
    if briefing.watch_info.region:
        lines.append(
            f'        <p style="margin:0 0 4px;color:#0f172a;font-size:13px;line-height:1.45;font-weight:800;">지역: {escape(briefing.watch_info.region)}</p>'
        )
    if briefing.watch_info.deal_period:
        lines.append(
            f'        <p style="margin:0 0 4px;color:#0f172a;font-size:13px;line-height:1.45;font-weight:800;">조회 기간: {escape(briefing.watch_info.deal_period)}</p>'
        )
    if briefing.watch_info.condition:
        lines.append(
            f'        <p style="margin:0;color:#0f172a;font-size:13px;line-height:1.45;font-weight:800;">조건: {escape(briefing.watch_info.condition)}</p>'
        )
    lines.append("      </div>")
    return "\n".join(lines)


def _real_estate_watch_info_markdown_lines(briefing: NotificationBriefing) -> list[str]:
    """Discord/Telegram 계열에서 감시 기준을 짧은 목록으로 보여준다."""
    lines = []
    if briefing.watch_info.region:
        lines.append(f"- 지역: {briefing.watch_info.region}")
    if briefing.watch_info.deal_period:
        lines.append(f"- 조회 기간: {briefing.watch_info.deal_period}")
    if briefing.watch_info.condition:
        lines.append(f"- 조건: {briefing.watch_info.condition}")
    return lines


def _real_estate_watch_info_plain_lines(briefing: NotificationBriefing) -> list[str]:
    """Markdown bullet 없이 같은 감시 정보 라인을 plain text 채널에 재사용한다."""
    return [
        line.removeprefix("- ")
        for line in _real_estate_watch_info_markdown_lines(briefing)
    ]


def _recruitment_info_plain_lines(briefing: NotificationBriefing) -> list[str]:
    """채용 plain text 본문 상단에 구독 조건과 데이터 출처를 고정한다."""
    lines = []
    if briefing.watch_info.condition:
        lines.append(f"구독 조건: {briefing.watch_info.condition}")
    if briefing.watch_info.data_source:
        lines.append(f"데이터 출처: {briefing.watch_info.data_source}")
    return lines


def _recruitment_info_markdown_lines(briefing: NotificationBriefing) -> list[str]:
    """채용 Discord 본문 상단에 들어갈 구독 정보 bullet을 만든다."""
    lines = []
    if briefing.watch_info.condition:
        lines.append(f"- 조건: {briefing.watch_info.condition}")
    if briefing.watch_info.data_source:
        lines.append(f"- 데이터 출처: {briefing.watch_info.data_source}")
    return lines


def _recruitment_info_card(briefing: NotificationBriefing) -> str:
    """채용 Email 상단에 구독 조건과 데이터 출처를 고정 위치로 보여준다."""
    if not briefing.watch_info.condition and not briefing.watch_info.data_source:
        return ""
    lines = ["      <div style=\"background:#eff6ff;border:1px solid #bfdbfe;border-radius:12px;padding:12px;margin:0 0 14px;\">",
             "        <p style=\"margin:0 0 6px;color:#1d4ed8;font-size:12px;line-height:1.2;font-weight:900;\">구독 정보</p>"]
    if briefing.watch_info.condition:
        lines.append(
            f'        <p style="margin:0 0 4px;color:#0f172a;font-size:13px;line-height:1.45;font-weight:800;">조건: {escape(briefing.watch_info.condition)}</p>'
        )
    if briefing.watch_info.data_source:
        lines.append(
            f'        <p style="margin:0;color:#0f172a;font-size:13px;line-height:1.45;font-weight:800;">데이터 출처: {escape(briefing.watch_info.data_source)}</p>'
        )
    lines.append("      </div>")
    return "\n".join(lines)


def _source_bullet_plain_lines(briefing: NotificationBriefing) -> list[str]:
    """Telegram처럼 plain text인 채널에서 공고명과 URL을 한 줄에 안정적으로 노출한다."""
    lines = []
    for source in briefing.sources:
        if not (source.url or source.description):
            continue
        if source.url:
            lines.append(f"- {source.label}: {source.url}")
        elif source.description:
            lines.append(f"- {source.label}: {source.description}")
    return lines


def _source_plain_lines(briefing: NotificationBriefing) -> list[str]:
    return [
        f"{source.label}\n{source.url}" if source.url else f"{source.label}: {source.description}"
        for source in briefing.sources
        if source.url or source.description
    ]


def _source_markdown_lines(briefing: NotificationBriefing) -> list[str]:
    return [
        f"- {source.label}: <{source.url}>" if source.url else f"- {source.label}: {source.description}"
        for source in briefing.sources
        if source.url or source.description
    ]


def _is_real_estate(briefing: NotificationBriefing) -> bool:
    return briefing.domain.strip().lower() in {"real-estate", "real_estate", "부동산"}


def _is_recruitment(briefing: NotificationBriefing) -> bool:
    """채용 도메인 별칭을 renderer 분기 기준으로 통일한다."""
    return briefing.domain.strip().lower() in {"recruitment", "job", "jobs", "채용"}
