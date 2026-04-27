package com.back.domain.adapter.out.notification;

import com.back.domain.application.port.out.SendNotificationDeliveryPort;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationSendResult;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.email.enabled", havingValue = "true")
public class EmailNotificationDeliveryAdapter implements SendNotificationDeliveryPort {

    private final JavaMailSender mailSender;
    private final NotificationClientProperties properties;

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL;
    }

    @Override
    public NotificationSendResult send(NotificationDelivery delivery) {
        try {
            if (isHtml(delivery.message())) {
                return sendHtml(delivery);
            }

            SimpleMailMessage message = new SimpleMailMessage();
            if (properties.getEmail().getFrom() != null && !properties.getEmail().getFrom().isBlank()) {
                message.setFrom(properties.getEmail().getFrom());
            }
            message.setTo(delivery.recipient());
            message.setSubject(delivery.title());
            message.setText(delivery.message());
            mailSender.send(message);
            return NotificationSendResult.success(null);
        } catch (MailException exception) {
            return NotificationSendResult.retryableFailure(exception.getMessage());
        }
    }

    private NotificationSendResult sendHtml(NotificationDelivery delivery) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (properties.getEmail().getFrom() != null && !properties.getEmail().getFrom().isBlank()) {
                helper.setFrom(properties.getEmail().getFrom());
            }
            helper.setTo(delivery.recipient());
            helper.setSubject(delivery.title());
            helper.setText(delivery.message(), true);
            mailSender.send(message);
            return NotificationSendResult.success(null);
        } catch (MailException | MessagingException exception) {
            return NotificationSendResult.retryableFailure(exception.getMessage());
        }
    }

    private boolean isHtml(String message) {
        return message != null && message.stripLeading().startsWith("<!doctype html>");
    }
}
