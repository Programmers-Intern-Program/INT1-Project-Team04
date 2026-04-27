package com.back.domain.application.service;

import com.back.domain.adapter.out.notification.NotificationClientProperties;
import com.back.domain.application.port.out.LoadDispatchableNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationSendResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcherService.class);

    private final LoadDispatchableNotificationDeliveryPort loadDeliveryPort;
    private final List<SendNotificationDeliveryPort> senders;
    private final SaveNotificationDeliveryPort saveDeliveryPort;
    private final NotificationClientProperties properties;
    private final MeterRegistry meterRegistry;

    public int dispatchPending(LocalDateTime now) {
        List<NotificationDelivery> deliveries = loadDeliveryPort.loadDispatchable(now);
        for (NotificationDelivery delivery : deliveries) {
            NotificationDelivery dispatched = dispatch(delivery, now);
            recordDispatch(dispatched);
            logDispatch(dispatched);
            saveDeliveryPort.save(dispatched);
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

    private void recordDispatch(NotificationDelivery delivery) {
        meterRegistry.counter(
                "notification.delivery.dispatch",
                "channel", delivery.channel().name(),
                "status", delivery.status().name()
        ).increment();
    }

    private void logDispatch(NotificationDelivery delivery) {
        if (delivery.status() == NotificationDeliveryStatus.SENT) {
            log.info(
                    "Notification delivery sent id={}, channel={}, userId={}, providerMessageId={}",
                    delivery.id(),
                    delivery.channel(),
                    delivery.userId(),
                    delivery.providerMessageId()
            );
            return;
        }

        if (delivery.status() == NotificationDeliveryStatus.RETRY) {
            log.warn(
                    "Notification delivery scheduled for retry id={}, channel={}, userId={}, attemptCount={}, nextRetryAt={}, reason={}",
                    delivery.id(),
                    delivery.channel(),
                    delivery.userId(),
                    delivery.attemptCount(),
                    delivery.nextRetryAt(),
                    delivery.failureReason()
            );
            return;
        }

        if (delivery.status() == NotificationDeliveryStatus.FAILED) {
            log.error(
                    "Notification delivery failed id={}, channel={}, userId={}, attemptCount={}, reason={}",
                    delivery.id(),
                    delivery.channel(),
                    delivery.userId(),
                    delivery.attemptCount(),
                    delivery.failureReason()
            );
        }
    }

    private int maxAttempts() {
        return Math.max(1, properties.getMaxAttempts());
    }

    private long retryDelaySeconds() {
        return Math.max(1, properties.getRetryDelaySeconds());
    }
}
