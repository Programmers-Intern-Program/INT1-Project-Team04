package com.back.domain.adapter.out.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notification")
public class NotificationClientProperties {

    private int maxAttempts = 3;
    private long retryDelaySeconds = 300;
    // 디스패처 한 틱에서 동시에 발송할 수 있는 알림 수 상한. 외부 채널 rate limit 보호용.
    private int dispatchConcurrencyLimit = 20;
    private final Telegram telegram = new Telegram();
    private final Discord discord = new Discord();

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

    public int getDispatchConcurrencyLimit() {
        return dispatchConcurrencyLimit;
    }

    public void setDispatchConcurrencyLimit(int dispatchConcurrencyLimit) {
        this.dispatchConcurrencyLimit = dispatchConcurrencyLimit;
    }

    public Telegram getTelegram() {
        return telegram;
    }

    public Discord getDiscord() {
        return discord;
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

    public static class Discord {
        private String botInviteUrl = "";

        public String getBotInviteUrl() {
            return botInviteUrl;
        }

        public void setBotInviteUrl(String botInviteUrl) {
            this.botInviteUrl = botInviteUrl;
        }
    }
}
