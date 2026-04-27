package com.back.domain.adapter.out.notification;

import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationSendResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramDmNotificationDeliveryAdapter implements SendNotificationDeliveryPort {

    private final RestClient.Builder restClientBuilder;
    private final NotificationClientProperties properties;

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.TELEGRAM_DM;
    }

    @Override
    public NotificationSendResult send(NotificationDelivery delivery) {
        if (properties.getTelegram().getBotToken() == null || properties.getTelegram().getBotToken().isBlank()) {
            return NotificationSendResult.permanentFailure("Telegram bot token is not configured");
        }

        try {
            RestClient restClient = restClientBuilder.build();
            Map<String, Object> response = restClient.post()
                    .uri(properties.getTelegram().getApiBaseUrl() + "/bot" + properties.getTelegram().getBotToken() + "/sendMessage")
                    .body(Map.of(
                            "chat_id", delivery.recipient(),
                            "text", delivery.message()
                    ))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (!Boolean.TRUE.equals(response == null ? null : response.get("ok"))) {
                return NotificationSendResult.retryableFailure("Telegram sendMessage response was not ok");
            }

            return NotificationSendResult.success(messageId(response));
        } catch (RestClientResponseException exception) {
            return fromResponseException(exception);
        } catch (RestClientException exception) {
            return NotificationSendResult.retryableFailure(exception.getMessage());
        }
    }

    private NotificationSendResult fromResponseException(RestClientResponseException exception) {
        if (exception.getStatusCode().is5xxServerError() || exception.getStatusCode().value() == 429) {
            return NotificationSendResult.retryableFailure(exception.getMessage());
        }

        return NotificationSendResult.permanentFailure(exception.getMessage());
    }

    @SuppressWarnings("unchecked")
    private String messageId(Map<String, Object> response) {
        Object result = response.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return null;
        }

        Object messageId = ((Map<String, Object>) resultMap).get("message_id");
        return messageId == null ? null : messageId.toString();
    }
}
