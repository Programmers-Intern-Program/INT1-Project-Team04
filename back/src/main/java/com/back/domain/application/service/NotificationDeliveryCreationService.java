package com.back.domain.application.service;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryCreationService {

    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LoadEnabledNotificationPreferencePort loadPreferencePort;
    private final LoadNotificationEndpointPort loadEndpointPort;
    private final SaveNotificationDeliveryPort saveDeliveryPort;

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
                NotificationDelivery delivery = new NotificationDelivery(
                        UuidGenerator.create(),
                        alertEvent.id(),
                        alertEvent.subscription().id(),
                        alertEvent.subscription().user().id(),
                        preference.channel(),
                        endpoint.targetAddress(),
                        alertEvent.title(),
                        formatMessage(alertEvent, preference.channel()),
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

    private String formatMessage(AlertEvent alertEvent, NotificationChannel channel) {
        if (channel == NotificationChannel.DISCORD_DM) {
            return formatDiscordMessage(alertEvent);
        }

        if (channel == NotificationChannel.TELEGRAM_DM) {
            return formatTelegramMessage(alertEvent);
        }

        return formatEmailMessage(alertEvent);
    }

    private String formatEmailMessage(AlertEvent alertEvent) {
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
                escapeHtml(formatCreatedAt(alertEvent))
        ).trim();
    }

    private String formatDiscordMessage(AlertEvent alertEvent) {
        StringBuilder message = new StringBuilder();
        message.append("**변화 감지**\n");
        message.append("**").append(alertEvent.title()).append("**\n");
        message.append(alertEvent.summary()).append("\n\n");

        appendSources(message, alertEvent.sources(), "**감지 항목 " + sourceCount(alertEvent.sources()) + "건**", true);
        appendMarkdownSection(message, "판단 근거", alertEvent.reason());
        appendMarkdownSection(message, "요청", "`" + escapeDiscordInlineCode(alertEvent.subscription().query()) + "`");
        appendMarkdownSection(message, "감지 시간", formatCreatedAt(alertEvent));
        return message.toString().trim();
    }

    private String formatTelegramMessage(AlertEvent alertEvent) {
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
        message.append("감지 시간: ").append(formatCreatedAt(alertEvent));
        return message.toString().trim();
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
}
