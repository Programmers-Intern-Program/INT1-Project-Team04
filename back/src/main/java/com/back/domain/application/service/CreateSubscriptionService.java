package com.back.domain.application.service;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationPreferencePort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SaveSubscriptionPort;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.user.User;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
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
    private final SaveSubscriptionPort saveSubscriptionPort;
    private final SaveSchedulePort saveSchedulePort;
    private final SaveNotificationEndpointPort saveNotificationEndpointPort;
    private final SaveNotificationPreferencePort saveNotificationPreferencePort;

    @Override
    public SubscriptionResult createForUser(Long userId, CreateSubscriptionCommand command) {
        User user = loadUserPort.loadById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        Domain domain = loadDomainPort.loadById(command.domainId())
                .orElseThrow(() -> new ApiException(ErrorCode.DOMAIN_NOT_FOUND));
        LocalDateTime nextRun = CronScheduleCalculator.nextRun(command.cronExpr(), LocalDateTime.now());

        Subscription subscription = saveSubscriptionPort.save(new Subscription(
                null,
                user,
                domain,
                command.query(),
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

        saveNotificationSettingsIfPresent(user, subscription, command);

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
            Subscription subscription,
            CreateSubscriptionCommand command
    ) {
        if (command.notificationChannel() == null
                || command.notificationTargetAddress() == null
                || command.notificationTargetAddress().isBlank()) {
            return;
        }

        saveNotificationEndpointPort.save(new NotificationEndpoint(
                null,
                user.id(),
                command.notificationChannel(),
                command.notificationTargetAddress().trim(),
                true
        ));
        saveNotificationPreferencePort.save(new NotificationPreference(
                null,
                subscription.id(),
                command.notificationChannel(),
                true
        ));
    }
}
