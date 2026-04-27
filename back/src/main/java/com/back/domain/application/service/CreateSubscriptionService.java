package com.back.domain.application.service;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadDuplicateSubscriptionPort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationPreferencePort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Domain Service] 구독 생성 요청을 처리하고 최초 실행 스케줄을 등록하는 Service
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreateSubscriptionService implements CreateSubscriptionUseCase {

    private final LoadUserPort loadUserPort;
    private final LoadDomainPort loadDomainPort;
    private final LoadDuplicateSubscriptionPort loadDuplicateSubscriptionPort;
    private final SaveSubscriptionPort saveSubscriptionPort;
    private final SaveSchedulePort saveSchedulePort;
    private final LoadNotificationEndpointPort loadNotificationEndpointPort;
    private final SaveNotificationEndpointPort saveNotificationEndpointPort;
    private final SaveNotificationPreferencePort saveNotificationPreferencePort;
    private final SaveNotificationDeliveryPort saveNotificationDeliveryPort;

    @Override
    public SubscriptionResult createForUser(Long userId, CreateSubscriptionCommand command) {
        User user = loadUserPort.loadById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        Domain domain = loadDomainPort.loadById(command.domainId())
                .orElseThrow(() -> new ApiException(ErrorCode.DOMAIN_NOT_FOUND));
        LocalDateTime nextRun = CronScheduleCalculator.nextRun(command.cronExpr(), LocalDateTime.now());
        if (loadDuplicateSubscriptionPort.existsActiveDuplicate(
                user.id(),
                domain.id(),
                normalizeQuery(command.query()),
                command.cronExpr(),
                command.notificationChannel()
        )) {
            throw new ApiException(ErrorCode.DUPLICATE_SUBSCRIPTION);
        }

        Subscription subscription = saveSubscriptionPort.save(new Subscription(
                null,
                user,
                domain,
                command.query(),
                "create",
                true,
                null
        ));
        Schedule schedule = saveSchedulePort.save(new Schedule(
                null,
                subscription,
                command.cronExpr(),
                null,
                nextRun
        ));

        saveNotificationSettingsIfPresent(user, domain, subscription, schedule, command);

        return new SubscriptionResult(
                subscription.id(),
                subscription.user().id(),
                subscription.domain().id(),
                subscription.query(),
                subscription.active(),
                subscription.createdAt(),
                schedule.id(),
                schedule.cronExpr(),
                schedule.nextRun()
        );
    }

    private void saveNotificationSettingsIfPresent(
            User user,
            Domain domain,
            Subscription subscription,
            Schedule schedule,
            CreateSubscriptionCommand command
    ) {
        Optional<NotificationEndpoint> endpoint = saveNotificationPreferenceIfPresent(user, subscription, command);
        endpoint.ifPresent(notificationEndpoint -> saveSubscriptionStartedDelivery(
                user,
                domain,
                subscription,
                schedule,
                notificationEndpoint
        ));
    }

    private Optional<NotificationEndpoint> saveNotificationPreferenceIfPresent(
            User user,
            Subscription subscription,
            CreateSubscriptionCommand command
    ) {
        if (command.notificationChannel() == null) {
            return Optional.empty();
        }

        NotificationEndpoint endpoint;
        if (command.notificationTargetAddress() != null && !command.notificationTargetAddress().isBlank()) {
            NotificationEndpoint existingEndpoint = loadNotificationEndpointPort
                    .loadEnabledByUserIdAndChannel(user.id(), command.notificationChannel())
                    .orElse(null);
            endpoint = saveNotificationEndpointPort.save(new NotificationEndpoint(
                    existingEndpoint == null ? null : existingEndpoint.id(),
                    user.id(),
                    command.notificationChannel(),
                    command.notificationTargetAddress().trim(),
                    true
            ));
        } else {
            endpoint = loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(user.id(), command.notificationChannel())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_ENDPOINT_NOT_CONNECTED));
        }

        saveNotificationPreferencePort.save(new NotificationPreference(
                null,
                subscription.id(),
                command.notificationChannel(),
                true
        ));

        return Optional.of(endpoint);
    }

    private void saveSubscriptionStartedDelivery(
            User user,
            Domain domain,
            Subscription subscription,
            Schedule schedule,
            NotificationEndpoint endpoint
    ) {
        if (!endpoint.enabled()) {
            return;
        }

        saveNotificationDeliveryPort.save(new NotificationDelivery(
                UuidGenerator.create(),
                "subscription-started-" + subscription.id(),
                subscription.id(),
                user.id(),
                endpoint.channel(),
                endpoint.targetAddress(),
                "알림 설정이 완료됐어요",
                formatSubscriptionStartedMessage(endpoint.channel(), domain, subscription, schedule),
                NotificationDeliveryStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        ));
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String formatSubscriptionStartedMessage(
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
                subscription.query(),
                formatDomainName(domain.name()),
                formatCronDescription(schedule.cronExpr())
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
                escapeDiscordInlineCode(subscription.query()),
                formatDomainName(domain.name()),
                formatCronDescription(schedule.cronExpr())
        );
    }

    private String formatEmailSubscriptionStartedMessage(
            Domain domain,
            Subscription subscription,
            Schedule schedule
    ) {
        String query = escapeHtml(subscription.query());
        String domainName = escapeHtml(formatDomainName(domain.name()));
        String cronDescription = escapeHtml(formatCronDescription(schedule.cronExpr()));

        return """
                <!doctype html>
                <html lang="ko">
                <body style="margin:0;background:#f7f2e8;color:#1c1917;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <div style="max-width:520px;margin:0 auto;padding:20px 14px;">
                    <div style="background:#fffdf7;border:1px solid #e6d9c3;border-radius:20px;padding:22px;box-shadow:0 12px 32px rgba(61,46,26,0.08);">
                      <span style="display:inline-block;background:#0f7a4f;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;line-height:1;font-weight:800;letter-spacing:-0.01em;">알림 설정 완료</span>
                      <h1 style="margin:14px 0 16px;font-size:22px;line-height:1.35;color:#211a12;font-weight:900;letter-spacing:-0.04em;">알림 설정이 완료됐어요</h1>

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

                      <p style="margin:0;color:#4d4033;font-size:14px;line-height:1.55;font-weight:700;">변화가 감지되면 정리해서 보내드릴게요.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                query,
                domainName,
                cronDescription
        );
    }

    private String formatDomainName(String domainName) {
        if (domainName == null || domainName.isBlank()) {
            return "선택한 영역";
        }

        String normalized = domainName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("real-estate")) {
            return "부동산";
        }
        if (normalized.contains("recruitment")) {
            return "채용";
        }
        if (normalized.contains("auction")) {
            return "경매/희소매물";
        }

        return domainName.trim();
    }

    private String formatCronDescription(String cronExpr) {
        return switch (cronExpr == null ? "" : cronExpr.trim()) {
            case "0 0 * * * *" -> "매시간 정각";
            case "0 0 9 * * *" -> "매일 오전 9시";
            case "0 0 9 * * MON-FRI" -> "평일 오전 9시";
            default -> "설정한 주기";
        };
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
