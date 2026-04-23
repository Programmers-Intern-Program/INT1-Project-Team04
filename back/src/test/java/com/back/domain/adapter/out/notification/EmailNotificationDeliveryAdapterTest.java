package com.back.domain.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationSendResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@DisplayName("Adapter: Email 알림 발송 테스트")
class EmailNotificationDeliveryAdapterTest {

    @Test
    @DisplayName("Adapter: JavaMailSender로 수신자, 제목, 본문을 전달한다")
    void sendsEmail() {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getEmail().setFrom("watch@example.com");
        EmailNotificationDeliveryAdapter adapter = new EmailNotificationDeliveryAdapter(mailSender, properties);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

        NotificationSendResult result = adapter.send(delivery(NotificationChannel.EMAIL, "user@example.com"));

        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(adapter.supports(NotificationChannel.EMAIL)).isTrue();
        assertThat(message.getFrom()).isEqualTo("watch@example.com");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("강남구 전세 매물 변화");
        assertThat(message.getText()).contains("조건에 맞는 신규 매물이 2건");
        assertThat(result.successful()).isTrue();
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
