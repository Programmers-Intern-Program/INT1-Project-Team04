package com.back.domain.application.service;

import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.global.common.UuidGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryCreationService {

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
                        formatMessage(alertEvent),
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

    private String formatMessage(AlertEvent alertEvent) {
        StringBuilder message = new StringBuilder();
        message.append(alertEvent.summary());

        if (alertEvent.reason() != null && !alertEvent.reason().isBlank()) {
            message.append("\n\n판단 근거: ").append(alertEvent.reason());
        }

        if (alertEvent.sourceUrl() != null && !alertEvent.sourceUrl().isBlank()) {
            message.append("\n\n원문: ").append(alertEvent.sourceUrl());
        }

        return message.toString();
    }
}
