package com.back.domain.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationSendResult;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.JavaMailSender;

@DisplayName("Adapter: Email 알림 발송 테스트")
class EmailNotificationDeliveryAdapterTest {

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(new ServerSetup(0, null, ServerSetup.PROTOCOL_SMTP));

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

    @Test
    @DisplayName("Adapter: fake SMTP 서버로 실제 메일을 전송한다")
    void sendsEmailThroughFakeSmtp() throws Exception {
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getEmail().setFrom("watch@example.com");
        EmailNotificationDeliveryAdapter adapter = new EmailNotificationDeliveryAdapter(fakeSmtpSender(), properties);

        NotificationSendResult result = adapter.send(delivery(NotificationChannel.EMAIL, "user@example.com"));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(result.successful()).isTrue();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getFrom()[0].toString()).isEqualTo("watch@example.com");
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(messages[0].getSubject()).isEqualTo("강남구 전세 매물 변화");
        assertThat(messages[0].getContent().toString()).contains("조건에 맞는 신규 매물이 2건");
    }

    @Test
    @DisplayName("Adapter: HTML 본문은 text/html 메일로 전송한다")
    void sendsHtmlEmailThroughFakeSmtp() throws Exception {
        NotificationClientProperties properties = new NotificationClientProperties();
        properties.getEmail().setFrom("watch@example.com");
        EmailNotificationDeliveryAdapter adapter = new EmailNotificationDeliveryAdapter(fakeSmtpSender(), properties);

        NotificationSendResult result = adapter.send(delivery(
                NotificationChannel.EMAIL,
                "user@example.com",
                "<!doctype html><html lang=\"ko\"><body><strong>요청</strong></body></html>"
        ));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(result.successful()).isTrue();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getContentType()).contains("text/html");
        assertThat(messages[0].getContent().toString()).contains("<strong>요청</strong>");
    }

    private JavaMailSender fakeSmtpSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());
        mailSender.setProtocol("smtp");

        Properties mailProperties = mailSender.getJavaMailProperties();
        mailProperties.put("mail.smtp.auth", "false");
        mailProperties.put("mail.smtp.starttls.enable", "false");
        return mailSender;
    }

    private NotificationDelivery delivery(NotificationChannel channel, String recipient) {
        return delivery(channel, recipient, "조건에 맞는 신규 매물이 2건 발견되었습니다.");
    }

    private NotificationDelivery delivery(NotificationChannel channel, String recipient, String message) {
        return new NotificationDelivery(
                "delivery-1",
                "alert-1",
                "sub-1",
                1L,
                channel,
                recipient,
                "강남구 전세 매물 변화",
                message,
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
