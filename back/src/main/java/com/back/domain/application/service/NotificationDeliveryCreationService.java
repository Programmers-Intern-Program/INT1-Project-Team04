package com.back.domain.application.service;

import com.back.domain.application.port.out.IssueBaselinePromoteTokenPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSource;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.global.common.UuidGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryCreationService {

    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LoadEnabledNotificationPreferencePort loadPreferencePort;
    private final LoadNotificationEndpointPort loadEndpointPort;
    private final SaveNotificationDeliveryPort saveDeliveryPort;
    private final IssueBaselinePromoteTokenPort issueBaselinePromoteTokenPort;

    // 필드 기본값은 Spring 컨테이너 없는 단위 테스트 호환용. @Value 주입 시 덮어쓰여진다.
    @Value("${app.notification.baseline-promote.base-url:http://localhost:8080}")
    private String baselinePromoteBaseUrl = "http://localhost:8080";

    @Value("${app.notification.baseline-promote.ttl-days:7}")
    private int baselinePromoteTtlDays = 7;

    public List<NotificationDelivery> createFor(AlertEvent alertEvent) {
        List<NotificationDelivery> deliveries = new ArrayList<>();
        List<NotificationPreference> preferences = loadPreferencePort.loadEnabledBySubscriptionId(
                alertEvent.subscription().id()
        );

        for (NotificationPreference preference : preferences) {
            if (!preference.enabled()) {
                continue;
            }

            loadEndpointPort.loadEnabledByUserIdAndChannel(
                    alertEvent.subscription().user().id(),
                    preference.channel()
            ).filter(NotificationEndpoint::enabled).ifPresent(endpoint -> {
                String promoteUrl = issuePromoteUrl(alertEvent);
                NotificationDelivery delivery = new NotificationDelivery(
                        UuidGenerator.create(),
                        alertEvent.id(),
                        alertEvent.subscription().id(),
                        alertEvent.subscription().user().id(),
                        preference.channel(),
                        endpoint.targetAddress(),
                        alertEvent.title(),
                        formatMessage(alertEvent, preference.channel(), promoteUrl),
                        NotificationDeliveryStatus.PENDING,
                        0,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now()
                );
                deliveries.add(saveDeliveryPort.save(delivery));
            });
        }

        return deliveries;
    }

    private String issuePromoteUrl(AlertEvent alertEvent) {
        // params_hash 는 메인 서버가 알 필요 없이 MCP 가 subscription_id 단독으로 모든 행을 갱신하면 충분.
        // 한 구독에 보통 단 하나의 snapshot row 만 존재하므로 정확성에 문제 없다.
        String token = issueBaselinePromoteTokenPort.issue(
                alertEvent.subscription().id(),
                null,
                alertEvent.subscription().user().id(),
                alertEvent.id(),
                Duration.ofDays(baselinePromoteTtlDays)
        );
        String prefix = baselinePromoteBaseUrl == null || baselinePromoteBaseUrl.isBlank()
                ? "http://localhost:8080"
                : baselinePromoteBaseUrl.replaceAll("/+$", "");
        return prefix + "/baseline-promote/" + token;
    }

    private String formatMessage(AlertEvent alertEvent, NotificationChannel channel, String promoteUrl) {
        if (channel == NotificationChannel.DISCORD_DM) {
            return formatDiscordMessage(alertEvent, promoteUrl);
        }

        if (channel == NotificationChannel.TELEGRAM_DM) {
            return formatTelegramMessage(alertEvent, promoteUrl);
        }

        return formatEmailMessage(alertEvent, promoteUrl);
    }

    private String formatEmailMessage(AlertEvent alertEvent, String promoteUrl) {
        if (isAiBriefing(alertEvent)) {
            return formatAiBriefingEmailMessage(alertEvent, promoteUrl);
        }

        return """
                <!doctype html>
                <html lang="ko">
                <body style="margin:0;background:#f7f2e8;color:#1c1917;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <div style="max-width:520px;margin:0 auto;padding:20px 14px;">
                    <div style="background:#fffdf7;border:1px solid #e6d9c3;border-radius:20px;padding:22px;box-shadow:0 12px 32px rgba(61,46,26,0.08);">
                      <span style="display:inline-block;background:#0f7a4f;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;line-height:1;font-weight:800;letter-spacing:-0.01em;">변화 감지</span>
                      <h1 style="margin:14px 0 8px;font-size:22px;line-height:1.35;color:#211a12;font-weight:900;letter-spacing:-0.04em;">%s</h1>
                      <p style="margin:0 0 16px;color:#4d4033;font-size:14px;line-height:1.55;font-weight:700;">%s</p>

                      <p style="margin:0 0 10px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:900;">감지 항목 %d건</p>
                      %s

                      %s

                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:separate;border-spacing:0 0;margin-top:14px;">
                        <tr>
                          <td style="width:50%%;vertical-align:top;padding-right:6px;">
                            <div style="background:#f5eedf;border-radius:14px;padding:14px;">
                              <p style="margin:0 0 7px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:800;">요청</p>
                              <p style="margin:0;color:#211a12;font-size:15px;line-height:1.35;font-weight:800;">%s</p>
                            </div>
                          </td>
                          <td style="width:50%%;vertical-align:top;padding-left:6px;">
                            <div style="background:#f5eedf;border-radius:14px;padding:14px;">
                              <p style="margin:0 0 7px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:800;">감지 시간</p>
                              <p style="margin:0;color:#211a12;font-size:15px;line-height:1.35;font-weight:800;">%s</p>
                            </div>
                          </td>
                        </tr>
                      </table>

                      %s
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(alertEvent.title()),
                escapeHtml(alertEvent.summary()),
                sourceCount(alertEvent.sources()),
                formatEmailSources(alertEvent.sources()),
                formatEmailReason(alertEvent.reason()),
                escapeHtml(alertEvent.subscription().query()),
                escapeHtml(formatCreatedAt(alertEvent)),
                emailPromoteSection(promoteUrl)
        ).trim();
    }

    private String formatAiBriefingEmailMessage(AlertEvent alertEvent, String promoteUrl) {
        return """
                <!doctype html>
                <html lang="ko">
                <body style="margin:0;background:#f7f2e8;color:#1c1917;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <div style="max-width:520px;margin:0 auto;padding:20px 14px;">
                    <div style="background:#fffdf7;border:1px solid #e6d9c3;border-radius:20px;padding:22px;box-shadow:0 12px 32px rgba(61,46,26,0.08);">
                      <span style="display:inline-block;background:#0f7a4f;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;line-height:1;font-weight:800;">AI 변화 브리핑</span>
                      <h1 style="margin:14px 0 14px;font-size:22px;line-height:1.35;color:#211a12;font-weight:900;">%s</h1>
                      <p style="margin:0 0 16px;color:#4d4033;font-size:14px;line-height:1.65;font-weight:700;">%s</p>
                      %s
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(alertEvent.title()),
                formatEmailMultilineText(stripAiBriefingHeader(alertEvent.summary())),
                emailPromoteSection(promoteUrl)
        ).trim();
    }

    private String formatDiscordMessage(AlertEvent alertEvent, String promoteUrl) {
        if (isAiBriefing(alertEvent)) {
            return alertEvent.summary().trim() + "\n\n" + discordPromoteSection(promoteUrl);
        }

        StringBuilder message = new StringBuilder();
        message.append("**변화 감지**\n");
        message.append("**").append(alertEvent.title()).append("**\n");
        message.append(alertEvent.summary()).append("\n\n");

        appendSources(message, alertEvent.sources(), "**감지 항목 " + sourceCount(alertEvent.sources()) + "건**", true);
        appendMarkdownSection(message, "판단 근거", alertEvent.reason());
        appendMarkdownSection(message, "요청", "`" + escapeDiscordInlineCode(alertEvent.subscription().query()) + "`");
        appendMarkdownSection(message, "감지 시간", formatCreatedAt(alertEvent));
        message.append(discordPromoteSection(promoteUrl));
        return message.toString().trim();
    }

    private String formatTelegramMessage(AlertEvent alertEvent, String promoteUrl) {
        if (isAiBriefing(alertEvent)) {
            return alertEvent.summary().trim() + "\n\n" + telegramPromoteSection(promoteUrl);
        }

        StringBuilder message = new StringBuilder();
        message.append("변화 감지\n");
        message.append(alertEvent.title()).append("\n");
        message.append("신규 ").append(sourceCount(alertEvent.sources())).append("건 감지\n\n");
        message.append(alertEvent.summary()).append("\n\n");

        List<AlertSource> sources = safeSources(alertEvent.sources());
        for (int index = 0; index < sources.size(); index++) {
            AlertSource source = sources.get(index);
            message.append(index + 1).append(". ").append(source.title()).append("\n");
            if (hasText(source.description())) {
                message.append(source.description()).append("\n");
            }
            if (hasText(source.url())) {
                message.append(source.url()).append("\n");
            }
            message.append("\n");
        }

        if (hasText(alertEvent.reason())) {
            message.append("판단 근거: ").append(alertEvent.reason()).append("\n");
        }
        message.append("요청: ").append(alertEvent.subscription().query()).append("\n");
        message.append("감지 시간: ").append(formatCreatedAt(alertEvent)).append("\n\n");
        message.append(telegramPromoteSection(promoteUrl));
        return message.toString().trim();
    }

    private String discordPromoteSection(String promoteUrl) {
        // <URL> 표기는 Discord 에서 unfurl(미리보기 카드)을 비활성화한다.
        return "**다음부터 이 상태를 기준으로 비교**\n"
                + "<" + promoteUrl + ">\n"
                + "_링크는 1회만 사용 가능합니다 (" + baselinePromoteTtlDays + "일 만료)_";
    }

    private String telegramPromoteSection(String promoteUrl) {
        return "다음부터 이 상태를 기준으로 비교: " + promoteUrl + "\n"
                + "링크는 1회만 사용 가능합니다 (" + baselinePromoteTtlDays + "일 만료)";
    }

    private String emailPromoteSection(String promoteUrl) {
        return """
                <div style="background:#fffaf0;border:1px solid #f0d8a0;border-radius:14px;padding:16px;margin-top:14px;">
                  <p style="margin:0 0 8px;color:#7a5a24;font-size:13px;font-weight:800;">기준 갱신</p>
                  <p style="margin:0 0 12px;color:#211a12;font-size:14px;line-height:1.55;font-weight:700;">
                    이 상태가 새로운 기준이 됩니다. 다음부터는 이 시점과 비교해 변화를 감지합니다.
                  </p>
                  <a href="%s" style="display:inline-block;background:#0f7a4f;color:#ffffff;border-radius:999px;padding:10px 18px;font-size:13px;font-weight:800;text-decoration:none;">
                    이 상태를 새 기준으로 설정
                  </a>
                  <p style="margin:8px 0 0;color:#7a5a24;font-size:11px;">링크는 1회만 사용 가능합니다 (%d일 만료)</p>
                </div>
                """.formatted(escapeHtml(promoteUrl), baselinePromoteTtlDays);
    }

    private String formatEmailSources(List<AlertSource> sources) {
        List<AlertSource> safeSources = safeSources(sources);
        if (safeSources.isEmpty()) {
            return """
                    <div style="background:#ffffff;border:1px solid #e8dcc7;border-radius:16px;padding:16px;margin-bottom:12px;">
                      <p style="margin:0;color:#4d4033;font-size:14px;line-height:1.5;font-weight:700;">표시할 항목이 없습니다.</p>
                    </div>
                    """;
        }

        StringBuilder items = new StringBuilder();
        for (int index = 0; index < safeSources.size(); index++) {
            AlertSource source = safeSources.get(index);
            items.append("""
                    <div style="background:#ffffff;border:1px solid #e8dcc7;border-radius:16px;padding:16px;margin-bottom:12px;">
                      <p style="margin:0 0 6px;color:#111827;font-size:16px;line-height:1.4;font-weight:900;">%d. %s</p>
                    """.formatted(index + 1, escapeHtml(source.title())));
            if (hasText(source.description())) {
                items.append("""
                      <p style="margin:0 0 8px;color:#4d4033;font-size:14px;line-height:1.5;font-weight:700;">%s</p>
                    """.formatted(escapeHtml(source.description())));
            }
            if (hasText(source.url())) {
                items.append("""
                      <a href="%s" style="color:#0369a1;font-size:13px;line-height:1.4;font-weight:800;text-decoration:none;">%s</a>
                    """.formatted(escapeHtml(source.url()), escapeHtml(source.url())));
            }
            items.append("</div>\n");
        }

        return items.toString();
    }

    private String formatEmailReason(String reason) {
        if (!hasText(reason)) {
            return "";
        }

        return """
                <div style="background:#f5eedf;border-radius:14px;padding:14px;margin-top:2px;">
                  <p style="margin:0 0 7px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:800;">판단 근거</p>
                  <p style="margin:0;color:#211a12;font-size:14px;line-height:1.5;font-weight:800;">%s</p>
                </div>
                """.formatted(escapeHtml(reason));
    }

    private void appendSources(StringBuilder message, List<AlertSource> sources, String label, boolean discordMarkdown) {
        List<AlertSource> safeSources = safeSources(sources);
        if (safeSources.isEmpty()) {
            return;
        }

        message.append(label).append("\n");
        for (int index = 0; index < safeSources.size(); index++) {
            AlertSource source = safeSources.get(index);
            message.append(index + 1).append(". ");
            if (discordMarkdown) {
                message.append("**").append(source.title()).append("**");
            } else {
                message.append(source.title());
            }
            message.append("\n");

            if (hasText(source.description())) {
                message.append("   ").append(source.description()).append("\n");
            }

            if (hasText(source.url())) {
                message.append("   ");
                if (discordMarkdown) {
                    message.append("<").append(source.url()).append(">");
                } else {
                    message.append(source.url());
                }
                message.append("\n");
            }

            message.append("\n");
        }
    }

    private void appendSection(StringBuilder message, String title, String body) {
        if (!hasText(body)) {
            return;
        }

        message.append(title).append("\n");
        message.append(body).append("\n\n");
    }

    private void appendMarkdownSection(StringBuilder message, String title, String body) {
        if (!hasText(body)) {
            return;
        }

        message.append("**").append(title).append("**\n");
        message.append(body).append("\n\n");
    }

    private int sourceCount(List<AlertSource> sources) {
        return safeSources(sources).size();
    }

    private List<AlertSource> safeSources(List<AlertSource> sources) {
        return sources == null ? List.of() : sources;
    }

    private String formatCreatedAt(AlertEvent alertEvent) {
        return alertEvent.createdAt().format(MESSAGE_TIME_FORMATTER);
    }

    private String stripAiBriefingHeader(String value) {
        if (!hasText(value)) {
            return "";
        }
        String[] lines = value.strip().split("\\R", -1);
        StringBuilder body = new StringBuilder();
        boolean skippedHeader = false;
        for (String line : lines) {
            if (!skippedHeader && line.strip().startsWith("[AI 변화 브리핑]")) {
                skippedHeader = true;
                continue;
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(line);
        }
        return body.toString().strip();
    }

    private String formatEmailMultilineText(String value) {
        return escapeHtml(value).replace("\n", "<br>");
    }

    private String escapeDiscordInlineCode(String value) {
        return value == null ? "" : value.replace('`', '\'');
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isAiBriefing(AlertEvent alertEvent) {
        return hasText(alertEvent.summary()) && alertEvent.summary().startsWith("[AI 변화 브리핑]");
    }
}
