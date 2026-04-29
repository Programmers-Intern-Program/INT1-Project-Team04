package com.back.domain.application.service;

import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionNotificationMessageFormatter {

    public String startedTitle() {
        return "알림 설정이 완료됐어요";
    }

    public String cancelledTitle() {
        return "알림 설정이 취소됐어요";
    }

    public String formatStarted(
            NotificationChannel channel,
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        if (channel == NotificationChannel.EMAIL) {
            return formatEmailSubscriptionStartedMessage(domain, subscription, schedule);
        }

        if (channel == NotificationChannel.DISCORD_DM) {
            return formatDiscordSubscriptionStartedMessage(domain, subscription, schedule);
        }

        return formatTelegramSubscriptionStartedMessage(domain, subscription, schedule);
    }

    public String formatCancelled(
            NotificationChannel channel,
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        if (channel == NotificationChannel.EMAIL) {
            return formatEmailSubscriptionCancelledMessage(domain, subscription, schedule);
        }

        if (channel == NotificationChannel.DISCORD_DM) {
            return formatDiscordSubscriptionCancelledMessage(domain, subscription, schedule);
        }

        return formatTelegramSubscriptionCancelledMessage(domain, subscription, schedule);
    }

    public String formatDomainName(String domainName) {
        if (domainName == null || domainName.isBlank()) {
            return "선택한 영역";
        }

        String normalized = domainName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("real-estate")) {
            return "부동산";
        }
        if (normalized.contains("law-regulation")) {
            return "법률/규제";
        }
        if (normalized.contains("recruitment")) {
            return "채용";
        }
        if (normalized.contains("auction")) {
            return "경매/희소매물";
        }

        return domainName.trim();
    }

    public String formatCronDescription(String cronExpr) {
        return formatCronDescription(cronExpr, "설정한 주기");
    }

    public String formatCronLabel(String cronExpr) {
        return formatCronDescription(cronExpr, cronExpr);
    }

    private String formatTelegramSubscriptionStartedMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return """
                알림 설정 완료

                요청: %s
                감시 영역: %s
                확인 주기: %s

                변화가 감지되면 이 채널로 핵심만 먼저 알려드릴게요.
                """.formatted(
                query(subscription),
                formatDomainName(domainName(domain)),
                formatCronDescription(cronExpr(schedule))
        );
    }

    private String formatTelegramSubscriptionCancelledMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return """
                알림 설정 취소

                요청: %s
                감시 영역: %s
                확인 주기: %s

                이제부터 이 조건으로는 알림을 보내지 않을게요.
                """.formatted(
                query(subscription),
                formatDomainName(domainName(domain)),
                formatCronDescription(cronExpr(schedule))
        );
    }

    private String formatDiscordSubscriptionStartedMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return """
                **알림 설정 완료**
                이제부터 요청하신 변화를 지켜볼게요.

                **요청**
                `%s`

                **감시 영역**
                %s

                **확인 주기**
                %s

                변화가 감지되면 새 항목과 근거 링크를 정리해서 보내드릴게요.
                """.formatted(
                escapeDiscordInlineCode(query(subscription)),
                formatDomainName(domainName(domain)),
                formatCronDescription(cronExpr(schedule))
        );
    }

    private String formatDiscordSubscriptionCancelledMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return """
                **알림 설정 취소**
                이제부터 이 조건으로는 알림을 보내지 않을게요.

                **요청**
                `%s`

                **감시 영역**
                %s

                **확인 주기**
                %s

                필요하면 언제든 다시 설정할 수 있어요.
                """.formatted(
                escapeDiscordInlineCode(query(subscription)),
                formatDomainName(domainName(domain)),
                formatCronDescription(cronExpr(schedule))
        );
    }

    private String formatEmailSubscriptionStartedMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return formatEmailSubscriptionMessage(
                "알림 설정 완료",
                startedTitle(),
                "#0f7a4f",
                query(subscription),
                domainName(domain),
                cronExpr(schedule),
                "변화가 감지되면 정리해서 보내드릴게요."
        );
    }

    private String formatEmailSubscriptionCancelledMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return formatEmailSubscriptionMessage(
                "알림 설정 취소",
                cancelledTitle(),
                "#7a2815",
                query(subscription),
                domainName(domain),
                cronExpr(schedule),
                "이제부터 이 조건으로는 알림을 보내지 않을게요."
        );
    }

    private String formatEmailSubscriptionMessage(
            String badge,
            String title,
            String badgeColor,
            String query,
            String domainName,
            String cronExpr,
            String footer
    ) {
        String escapedQuery = escapeHtml(query);
        String escapedDomainName = escapeHtml(formatDomainName(domainName));
        String cronDescription = escapeHtml(formatCronDescription(cronExpr));
        String escapedFooter = escapeHtml(footer);

        return """
                <!doctype html>
                <html lang="ko">
                <body style="margin:0;background:#f7f2e8;color:#1c1917;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <div style="max-width:520px;margin:0 auto;padding:20px 14px;">
                    <div style="background:#fffdf7;border:1px solid #e6d9c3;border-radius:20px;padding:22px;box-shadow:0 12px 32px rgba(61,46,26,0.08);">
                      <span style="display:inline-block;background:%s;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;line-height:1;font-weight:800;letter-spacing:-0.01em;">%s</span>
                      <h1 style="margin:14px 0 16px;font-size:22px;line-height:1.35;color:#211a12;font-weight:900;letter-spacing:-0.04em;">%s</h1>

                      <div style="background:#ffffff;border:1px solid #e8dcc7;border-radius:16px;padding:16px;margin-bottom:14px;">
                        <p style="margin:0 0 8px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:800;">요청</p>
                        <p style="margin:0;font-size:17px;line-height:1.45;font-weight:900;color:#111827;letter-spacing:-0.03em;">%s</p>
                      </div>

                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:separate;border-spacing:0 0;margin-bottom:16px;">
                        <tr>
                          <td style="width:50%%;vertical-align:top;padding-right:6px;">
                            <div style="background:#f5eedf;border-radius:14px;padding:14px;">
                              <p style="margin:0 0 7px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:800;">감시 영역</p>
                              <p style="margin:0;color:#211a12;font-size:15px;line-height:1.35;font-weight:800;">%s</p>
                            </div>
                          </td>
                          <td style="width:50%%;vertical-align:top;padding-left:6px;">
                            <div style="background:#f5eedf;border-radius:14px;padding:14px;">
                              <p style="margin:0 0 7px;color:#7a5a24;font-size:12px;line-height:1.2;font-weight:800;">확인 주기</p>
                              <p style="margin:0;color:#211a12;font-size:15px;line-height:1.35;font-weight:800;">%s</p>
                            </div>
                          </td>
                        </tr>
                      </table>

                      <p style="margin:0;color:#4d4033;font-size:14px;line-height:1.55;font-weight:700;">%s</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                badgeColor,
                badge,
                title,
                escapedQuery,
                escapedDomainName,
                cronDescription,
                escapedFooter
        );
    }

    private String formatCronDescription(String cronExpr, String fallback) {
        return switch (cronExpr == null ? "" : cronExpr.trim()) {
            case "0 0 * * * *" -> "매시간 정각";
            case "0 0 9 * * *" -> "매일 오전 9시";
            case "0 0 9 * * MON-FRI" -> "평일 오전 9시";
            default -> fallback;
        };
    }

    private String query(Subscription subscription) {
        return subscription == null ? "" : subscription.query();
    }

    private String domainName(Domain domain) {
        return domain == null ? null : domain.name();
    }

    private String cronExpr(Schedule schedule) {
        return schedule == null ? null : schedule.cronExpr();
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
}
