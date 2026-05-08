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
    private final SubscriptionNotificationMessageFormatter notificationMessageFormatter;

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
                notificationMessageFormatter.startedTitle(),
                notificationMessageFormatter.formatStarted(endpoint.channel(), domain, subscription, schedule),
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
}
