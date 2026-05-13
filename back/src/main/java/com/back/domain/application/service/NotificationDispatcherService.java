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
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcherService.class);

    private final LoadDispatchableNotificationDeliveryPort loadDeliveryPort;
    private final List<SendNotificationDeliveryPort> senders;
    private final SaveNotificationDeliveryPort saveDeliveryPort;
    private final NotificationClientProperties properties;
    private final MeterRegistry meterRegistry;
    // 디스패처 전역으로 동시 발송 수를 제한한다. 호출 단위로 new Semaphore()를 만들면
    // 틱마다 별도 한도가 생겨 외부 제공자 rate limit(예: 텔레그램 글로벌 ~30 msg/s) 보호가 풀린다.
    private final Semaphore concurrencyGuard;

    public NotificationDispatcherService(
            LoadDispatchableNotificationDeliveryPort loadDeliveryPort,
            List<SendNotificationDeliveryPort> senders,
            SaveNotificationDeliveryPort saveDeliveryPort,
            NotificationClientProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.loadDeliveryPort = loadDeliveryPort;
        this.senders = senders;
        this.saveDeliveryPort = saveDeliveryPort;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.concurrencyGuard = new Semaphore(Math.max(1, properties.getDispatchConcurrencyLimit()));
    }

    public int dispatchPending(LocalDateTime now) {
        List<NotificationDelivery> deliveries = loadDeliveryPort.loadDispatchable(now);
        return dispatch(deliveries, now);
    }

    public int dispatch(List<NotificationDelivery> deliveries, LocalDateTime now) {
        // VT per delivery: send()는 BE→MCP→외부 채널로 이어지는 블로킹 I/O다.
        // try-with-resources의 executor.close()가 모든 VT 완료를 기다리므로 반환 시점 보장은 동일하다.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (NotificationDelivery delivery : deliveries) {
                executor.submit(() -> dispatchAndPersist(delivery, now));
            }
        }
        return deliveries.size();
    }

    private void dispatchAndPersist(NotificationDelivery delivery, LocalDateTime now) {
        concurrencyGuard.acquireUninterruptibly();
        try {
            NotificationDelivery dispatched = dispatchOne(delivery, now);
            recordDispatch(dispatched);
            logDispatch(dispatched);
            saveDeliveryPort.save(dispatched);
        } catch (RuntimeException exception) {
            // 한 delivery의 실패(예: save 단계 예외)가 같은 틱의 다른 delivery 발송을 막지 않도록 격리한다.
            log.error("Notification delivery dispatch failed unexpectedly id={}", delivery.id(), exception);
        } finally {
            concurrencyGuard.release();
        }
    }

    private NotificationDelivery dispatchOne(NotificationDelivery delivery, LocalDateTime now) {
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
