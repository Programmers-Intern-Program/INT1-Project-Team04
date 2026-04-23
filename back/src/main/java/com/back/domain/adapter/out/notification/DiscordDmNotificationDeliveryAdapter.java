package com.back.domain.adapter.out.notification;

import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationSendResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.discord.enabled", havingValue = "true")
public class DiscordDmNotificationDeliveryAdapter implements SendNotificationDeliveryPort {

    private final RestClient.Builder restClientBuilder;
    private final NotificationClientProperties properties;

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.DISCORD_DM;
    }

    @Override
    public NotificationSendResult send(NotificationDelivery delivery) {
        if (properties.getDiscord().getBotToken() == null || properties.getDiscord().getBotToken().isBlank()) {
            return NotificationSendResult.permanentFailure("Discord bot token is not configured");
        }

        try {
            RestClient restClient = restClientBuilder.build();
            Map<String, Object> dmChannel = restClient.post()
                    .uri(properties.getDiscord().getApiBaseUrl() + "/users/@me/channels")
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + properties.getDiscord().getBotToken())
                    .body(Map.of("recipient_id", delivery.recipient()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            String channelId = stringValue(dmChannel, "id");
            if (channelId == null || channelId.isBlank()) {
                return NotificationSendResult.retryableFailure("Discord DM channel response did not include id");
            }

            Map<String, Object> message = restClient.post()
                    .uri(properties.getDiscord().getApiBaseUrl() + "/channels/" + channelId + "/messages")
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + properties.getDiscord().getBotToken())
                    .body(Map.of("content", delivery.message()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            return NotificationSendResult.success(stringValue(message, "id"));
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

    private String stringValue(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }

        Object value = source.get(key);
        return value == null ? null : value.toString();
    }
}
