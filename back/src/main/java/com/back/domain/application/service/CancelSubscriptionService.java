package com.back.domain.application.service;

import com.back.domain.application.port.in.CancelSubscriptionUseCase;
import com.back.domain.application.port.out.DeactivateSubscriptionPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.LoadSubscriptionPort;
import com.back.domain.application.port.out.LoadSubscriptionSchedulePort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CancelSubscriptionService implements CancelSubscriptionUseCase {

    private final LoadSubscriptionPort loadSubscriptionPort;
    private final DeactivateSubscriptionPort deactivateSubscriptionPort;
    private final LoadSubscriptionSchedulePort loadSubscriptionSchedulePort;
    private final LoadEnabledNotificationPreferencePort loadEnabledNotificationPreferencePort;
    private final LoadNotificationEndpointPort loadNotificationEndpointPort;
    private final SaveNotificationDeliveryPort saveNotificationDeliveryPort;
    private final SubscriptionNotificationMessageFormatter notificationMessageFormatter;

    @Override
    public void cancelForUser(Long userId, String subscriptionId) {
        Subscription subscription = loadSubscriptionPort.loadActiveByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        Schedule schedule = loadSubscriptionSchedulePort.loadFirstBySubscriptionId(subscription.id())
                .orElse(null);

        saveSubscriptionCancelledDeliveryIfPresent(subscription, schedule);
        deactivateSubscriptionPort.deactivate(subscription.id());
    }

    private void saveSubscriptionCancelledDeliveryIfPresent(Subscription subscription, Schedule schedule) {
        loadEnabledNotificationPreferencePort.loadEnabledBySubscriptionId(subscription.id())
                .stream()
                .findFirst()
                .flatMap(preference -> loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(
                        subscription.user().id(),
                        preference.channel()
                ))
                .ifPresent(endpoint -> saveSubscriptionCancelledDelivery(subscription, schedule, endpoint));
    }

    private void saveSubscriptionCancelledDelivery(
            Subscription subscription,
            Schedule schedule,
            NotificationEndpoint endpoint
    ) {
        saveNotificationDeliveryPort.save(new NotificationDelivery(
                UuidGenerator.create(),
                "subscription-cancelled-" + subscription.id(),
                subscription.id(),
                subscription.user().id(),
                endpoint.channel(),
                endpoint.targetAddress(),
                notificationMessageFormatter.cancelledTitle(),
                notificationMessageFormatter.formatCancelled(
                        endpoint.channel(),
                        subscription.domain(),
                        subscription,
                        schedule
                ),
                NotificationDeliveryStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        ));
    }
}
