package com.back.domain.adapter.out.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notification")
public class NotificationClientProperties {

    private int maxAttempts = 3;
    private long retryDelaySeconds = 300;
    private final Telegram telegram = new Telegram();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public void setRetryDelaySeconds(long retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public Telegram getTelegram() {
        return telegram;
    }

    public static class Telegram {
        private String botUsername = "";

        public String getBotUsername() {
            return botUsername;
        }

        public void setBotUsername(String botUsername) {
            this.botUsername = botUsername;
        }
    }
}
