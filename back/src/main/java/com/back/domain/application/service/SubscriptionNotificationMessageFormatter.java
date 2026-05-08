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
        return "알림 설정 완료";
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
        return "변화 감지 시";
    }

    public String formatCronLabel(String cronExpr) {
        return "변화 감지 시";
    }

    private String formatTelegramSubscriptionStartedMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        return """
                요청: %s
                감시 영역: %s
                알림 방식: %s

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
                알림 방식: %s

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
                이제부터 요청하신 변화를 지켜볼게요.

                **요청**
                `%s`

                **감시 영역**
                %s

                **알림 방식**
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

                **알림 방식**
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
                "구독 준비 완료",
                "요청하신 조건을 지켜볼 준비가 끝났어요",
                "#2563eb",
                query(subscription),
                domainName(domain),
                cronExpr(schedule),
                "변화가 감지되면 핵심 변화, 판단 근거, 다음에 볼 지표를 정리해 보내드릴게요."
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

        // 구독 시작/해지 메일도 변화 브리핑과 같은 카드 톤을 사용해 채널 경험을 맞춘다.
        return """
                <!doctype html>
                <html lang="ko">
                <body style="margin:0;background:#f4f7fb;color:#0f172a;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <div style="max-width:560px;margin:0 auto;padding:20px 14px;">
                    <div style="background:#ffffff;border:1px solid #dbe4f0;border-radius:16px;padding:22px;box-shadow:0 12px 30px rgba(15,23,42,0.08);">
                      <span style="display:inline-block;background:%s;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;line-height:1;font-weight:800;">%s</span>
                      <h1 style="margin:14px 0 10px;font-size:22px;line-height:1.35;color:#0f172a;font-weight:900;">%s</h1>
                      <p style="margin:0 0 16px;color:#334155;font-size:14px;line-height:1.6;font-weight:700;">설정한 조건과 채널을 한 번에 확인하세요.</p>

                      <div style="background:#eff6ff;border:1px solid #bfdbfe;border-radius:12px;padding:14px;margin-bottom:14px;">
                        <p style="margin:0 0 8px;color:#1d4ed8;font-size:12px;line-height:1.2;font-weight:900;">감시 대상</p>
                        <p style="margin:0;font-size:16px;line-height:1.45;font-weight:900;color:#0f172a;">%s</p>
                      </div>

                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;margin-bottom:16px;">
                        <tr>
                          <th style="text-align:left;padding:10px 0;border-top:1px solid #e2e8f0;color:#475569;font-size:13px;font-weight:800;">감시 영역</th>
                          <td style="text-align:right;padding:10px 0;border-top:1px solid #e2e8f0;color:#0f172a;font-size:13px;font-weight:900;">%s</td>
                        </tr>
                        <tr>
                          <th style="text-align:left;padding:10px 0;border-top:1px solid #e2e8f0;color:#475569;font-size:13px;font-weight:800;">발송 조건</th>
                          <td style="text-align:right;padding:10px 0;border-top:1px solid #e2e8f0;color:#0f172a;font-size:13px;font-weight:900;">%s</td>
                        </tr>
                      </table>

                      <div style="background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;padding:14px;">
                        <p style="margin:0 0 6px;color:#1d4ed8;font-size:12px;line-height:1.2;font-weight:900;">앞으로 이렇게 알려드려요</p>
                        <p style="margin:0;color:#334155;font-size:14px;line-height:1.6;font-weight:700;">%s</p>
                      </div>
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
