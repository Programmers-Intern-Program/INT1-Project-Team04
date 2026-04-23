package com.back.domain.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationSendResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Adapter: Telegram DM 알림 발송 테스트")
class TelegramDmNotificationDeliveryAdapterTest {

    @Test
    @DisplayName("Adapter: Telegram sendMessage API 성공 응답을 provider message id로 반환한다")
    void sendsTelegramMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getTelegram().setBotToken("telegram-token");
        properties.getTelegram().setApiBaseUrl("https://api.telegram.test");
        TelegramDmNotificationDeliveryAdapter adapter = new TelegramDmNotificationDeliveryAdapter(builder, properties);

        server.expect(requestTo("https://api.telegram.test/bottelegram-token/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"chat_id\":\"123456789\"")))
                .andExpect(content().string(containsString("\"text\":\"조건에 맞는 신규 매물이 2건 발견되었습니다.\"")))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":123}}", MediaType.APPLICATION_JSON));

        NotificationSendResult result = adapter.send(delivery(NotificationChannel.TELEGRAM_DM, "123456789"));

        assertThat(adapter.supports(NotificationChannel.TELEGRAM_DM)).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("123");
        server.verify();
    }

    private NotificationDelivery delivery(NotificationChannel channel, String recipient) {
        return new NotificationDelivery(
                "delivery-1",
                "alert-1",
                "sub-1",
                1L,
                channel,
                recipient,
                "강남구 전세 매물 변화",
                "조건에 맞는 신규 매물이 2건 발견되었습니다.",
                NotificationDeliveryStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        );
    }
}
