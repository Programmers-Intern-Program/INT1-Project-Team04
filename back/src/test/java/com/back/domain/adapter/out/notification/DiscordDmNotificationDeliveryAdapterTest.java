package com.back.domain.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Adapter: Discord DM 알림 발송 테스트")
class DiscordDmNotificationDeliveryAdapterTest {

    @Test
    @DisplayName("Adapter: Discord DM 채널을 열고 메시지 생성 성공 응답을 반환한다")
    void sendsDiscordDm() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getDiscord().setBotToken("discord-token");
        properties.getDiscord().setApiBaseUrl("https://discord.test/api/v10");
        DiscordDmNotificationDeliveryAdapter adapter = new DiscordDmNotificationDeliveryAdapter(builder, properties);

        server.expect(requestTo("https://discord.test/api/v10/users/@me/channels"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bot discord-token"))
                .andExpect(content().string(containsString("\"recipient_id\":\"987654321\"")))
                .andRespond(withSuccess("{\"id\":\"dm-channel-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://discord.test/api/v10/channels/dm-channel-1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bot discord-token"))
                .andExpect(content().string(containsString("\"content\":\"조건에 맞는 신규 매물이 2건 발견되었습니다.\"")))
                .andRespond(withSuccess("{\"id\":\"message-1\"}", MediaType.APPLICATION_JSON));

        NotificationSendResult result = adapter.send(delivery(NotificationChannel.DISCORD_DM, "987654321"));

        assertThat(adapter.supports(NotificationChannel.DISCORD_DM)).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("message-1");
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
