package com.back.domain.application.service;

import com.back.domain.adapter.out.notification.NotificationClientProperties;
import com.back.domain.application.port.out.LoadDispatchableNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationSendResult;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDispatcherService {

    private final LoadDispatchableNotificationDeliveryPort loadDeliveryPort;
    private final List<SendNotificationDeliveryPort> senders;
    private final SaveNotificationDeliveryPort saveDeliveryPort;
    private final NotificationClientProperties properties;

    public int dispatchPending(LocalDateTime now) {
        List<NotificationDelivery> deliveries = loadDeliveryPort.loadDispatchable(now);
        for (NotificationDelivery delivery : deliveries) {
            saveDeliveryPort.save(dispatch(delivery, now));
        }
        return deliveries.size();
    }

    private NotificationDelivery dispatch(NotificationDelivery delivery, LocalDateTime now) {
        SendNotificationDeliveryPort sender = senders.stream()
                .filter(candidate -> candidate.supports(delivery.channel()))
                .findFirst()
                .orElse(null);

        if (sender == null) {
            return delivery.markFailed("No notification sender for channel " + delivery.channel());
        }

        NotificationSendResult result;
        try {
            result = sender.send(delivery);
        } catch (RuntimeException exception) {
            result = NotificationSendResult.retryableFailure(exception.getMessage());
        }

        if (result.successful()) {
            return delivery.markSent(now, result.providerMessageId());
        }

        if (result.retryable() && delivery.attemptCount() + 1 < maxAttempts()) {
            return delivery.markRetry(now.plusSeconds(retryDelaySeconds()), result.failureReason());
        }

        return delivery.markFailed(result.failureReason());
    }

    private int maxAttempts() {
        return Math.max(1, properties.getMaxAttempts());
    }

    private long retryDelaySeconds() {
        return Math.max(1, properties.getRetryDelaySeconds());
    }
}
