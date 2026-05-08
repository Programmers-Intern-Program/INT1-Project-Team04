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
    if channel == NotificationChannel.DISCORD_DM:
        return RenderedNotification(_generic_discord_text(briefing), None)
    if channel == NotificationChannel.EMAIL:
        return RenderedNotification(_generic_email_html(briefing), briefing.title)
    return RenderedNotification(_generic_plain_text(briefing), None)


def _render_real_estate(briefing: NotificationBriefing, channel: NotificationChannel) -> RenderedNotification:
    lines = _real_estate_lines(briefing)
    plain = "\n".join(lines).strip()
    if channel == NotificationChannel.DISCORD_DM:
        return RenderedNotification("\n".join([f"**{briefing.title}**", "", *lines[2:]]).strip(), None)
    if channel == NotificationChannel.EMAIL:
        rows = "".join(
            f"<tr><th>{escape(label)}</th><td>{escape(value)}</td></tr>"
            for label, value in _real_estate_pairs(briefing)
        )
        return RenderedNotification(f"""<!doctype html>
<html lang="ko">
<body style="margin:0;background:#f4f7fb;color:#0f172a;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
  <div style="max-width:560px;margin:0 auto;padding:20px 14px;">
    <div style="background:#ffffff;border:1px solid #dbe4f0;border-radius:16px;padding:22px;">
      <span style="display:inline-block;background:#2563eb;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;font-weight:800;">부동산 변화 브리핑</span>
      <h1 style="margin:14px 0 10px;font-size:22px;line-height:1.35;color:#0f172a;font-weight:900;">{escape(briefing.title)}</h1>
      <p style="margin:0 0 16px;color:#334155;font-size:14px;line-height:1.65;font-weight:700;">{escape(briefing.summary)}</p>
      <table role="presentation" width="100%" style="border-collapse:collapse;margin:0;">{rows}</table>
    </div>
  </div>
</body>
</html>""", briefing.title)
    return RenderedNotification(plain, None)


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


def _real_estate_lines(briefing: NotificationBriefing) -> list[str]:
    lines = [briefing.title, "", briefing.summary]
    lines.extend(f"{label}: {value}" for label, value in _real_estate_pairs(briefing))
    return lines


def _real_estate_pairs(briefing: NotificationBriefing) -> list[tuple[str, str]]:
    labels = ("거래 건수", "최고가/최저가", "데이터 출처")
    return [
        (label, change.value)
        for label in labels
        for change in briefing.changes
        if change.label == label and change.value
    ]


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
