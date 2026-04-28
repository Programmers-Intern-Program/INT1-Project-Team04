package com.back.domain.application.service;

import com.back.domain.adapter.out.notification.NotificationClientProperties;
import com.back.domain.adapter.out.persistence.notification.NotificationConnectionTokenJpaEntity;
import com.back.domain.adapter.out.persistence.notification.NotificationConnectionTokenJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserOAuthConnectionJpaRepository;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.SaveNotificationEndpointPort;
import com.back.domain.application.result.NotificationEndpointConnectionResult;
import com.back.domain.application.result.NotificationEndpointStatusResult;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationEndpointConnectionService {

    private static final Duration TELEGRAM_CONNECT_TOKEN_TTL = Duration.ofMinutes(15);

    private final LoadNotificationEndpointPort loadNotificationEndpointPort;
    private final SaveNotificationEndpointPort saveNotificationEndpointPort;
    private final UserJpaRepository userRepository;
    private final UserOAuthConnectionJpaRepository oauthConnectionRepository;
    private final NotificationConnectionTokenJpaRepository connectionTokenRepository;
    private final NotificationClientProperties notificationClientProperties;

    @Transactional(readOnly = true)
    public List<NotificationEndpointStatusResult> loadStatuses(Long userId) {
        return Arrays.stream(NotificationChannel.values())
                .map(channel -> loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(userId, channel)
                        .map(endpoint -> new NotificationEndpointStatusResult(
                                channel,
                                true,
                                targetLabel(channel, endpoint.targetAddress())
                        ))
                        .orElseGet(() -> new NotificationEndpointStatusResult(channel, false, null)))
                .toList();
    }

    public NotificationEndpointConnectionResult connectDiscord(Long userId, String authorizationUrl) {
        return oauthConnectionRepository.findFirstByUserIdAndProvider(userId, OAuthProvider.DISCORD)
                .map(connection -> {
                    saveOrUpdateEndpoint(userId, NotificationChannel.DISCORD_DM, connection.getProviderUserId());
                    return new NotificationEndpointConnectionResult(
                            NotificationChannel.DISCORD_DM,
                            true,
                            "연결됨",
                            null,
                            null,
                            "Discord DM 연결이 완료되었습니다."
                    );
                })
                .orElseGet(() -> new NotificationEndpointConnectionResult(
                        NotificationChannel.DISCORD_DM,
                        false,
                        null,
                        null,
                        authorizationUrl,
                        "Discord 로그인 후 알림 연결을 완료할 수 있습니다."
                ));
    }

    public NotificationEndpointConnectionResult completeDiscordConnection(Long userId, OAuthUserProfile profile) {
        if (profile.provider() != OAuthProvider.DISCORD
                || profile.providerUserId() == null
                || profile.providerUserId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        Optional<UserOAuthConnectionJpaEntity> existingIdentity = oauthConnectionRepository
                .findByProviderAndProviderUserId(OAuthProvider.DISCORD, profile.providerUserId());
        if (existingIdentity.isPresent() && !existingIdentity.get().getUser().getId().equals(userId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        if (existingIdentity.isEmpty()) {
            Optional<UserOAuthConnectionJpaEntity> existingUserDiscord = oauthConnectionRepository
                    .findFirstByUserIdAndProvider(userId, OAuthProvider.DISCORD);
            if (existingUserDiscord.isPresent()
                    && !existingUserDiscord.get().getProviderUserId().equals(profile.providerUserId())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }

            UserJpaEntity user = userRepository.findById(userId)
                    .filter(storedUser -> storedUser.getDeletedAt() == null)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
            oauthConnectionRepository.save(new UserOAuthConnectionJpaEntity(
                    user,
                    OAuthProvider.DISCORD,
                    profile.providerUserId(),
                    profile.email() == null || profile.email().isBlank() ? user.getEmail() : profile.email(),
                    profile.accessToken()
            ));
        }

        saveOrUpdateEndpoint(userId, NotificationChannel.DISCORD_DM, profile.providerUserId());
        return new NotificationEndpointConnectionResult(
                NotificationChannel.DISCORD_DM,
                true,
                "연결됨",
                null,
                null,
                "Discord DM 연결이 완료되었습니다."
        );
    }

    public NotificationEndpointConnectionResult startTelegramConnection(Long userId) {
        String botUsername = normalizedTelegramBotUsername();
        String token = UUID.randomUUID().toString();
        connectionTokenRepository.save(new NotificationConnectionTokenJpaEntity(
                token,
                userId,
                NotificationChannel.TELEGRAM_DM,
                LocalDateTime.now().plus(TELEGRAM_CONNECT_TOKEN_TTL)
        ));

        return new NotificationEndpointConnectionResult(
                NotificationChannel.TELEGRAM_DM,
                false,
                null,
                "https://t.me/" + botUsername + "?start=" + token,
                null,
                "Telegram 봇을 열어 연결을 완료해 주세요."
        );
    }

    public NotificationEndpointConnectionResult completeTelegramConnection(String text, Long chatId) {
        Optional<String> token = readTelegramStartToken(text);
        if (token.isEmpty() || chatId == null) {
            return telegramWebhookIgnored();
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<NotificationConnectionTokenJpaEntity> connectionToken = connectionTokenRepository
                .findById(token.get())
                .filter(storedToken -> storedToken.getChannel() == NotificationChannel.TELEGRAM_DM)
                .filter(storedToken -> storedToken.isUsable(now));
        if (connectionToken.isEmpty()) {
            return telegramWebhookIgnored();
        }

        NotificationConnectionTokenJpaEntity storedToken = connectionToken.get();
        saveOrUpdateEndpoint(storedToken.getUserId(), NotificationChannel.TELEGRAM_DM, chatId.toString());
        storedToken.markUsed(now);

        return new NotificationEndpointConnectionResult(
                NotificationChannel.TELEGRAM_DM,
                true,
                "연결됨",
                null,
                null,
                "Telegram DM 연결이 완료되었습니다."
        );
    }

    public NotificationEndpointConnectionResult disconnect(Long userId, NotificationChannel channel) {
        loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(userId, channel)
                .ifPresent(endpoint -> saveNotificationEndpointPort.save(new NotificationEndpoint(
                        endpoint.id(),
                        endpoint.userId(),
                        endpoint.channel(),
                        endpoint.targetAddress(),
                        false
                )));

        return new NotificationEndpointConnectionResult(
                channel,
                false,
                null,
                null,
                null,
                channelLabel(channel) + " 연결이 해제되었습니다."
        );
    }

    private void saveOrUpdateEndpoint(Long userId, NotificationChannel channel, String targetAddress) {
        NotificationEndpoint existingEndpoint = loadNotificationEndpointPort
                .loadEnabledByUserIdAndChannel(userId, channel)
                .orElse(null);
        saveNotificationEndpointPort.save(new NotificationEndpoint(
                existingEndpoint == null ? null : existingEndpoint.id(),
                userId,
                channel,
                targetAddress,
                true
        ));
    }

    private String normalizedTelegramBotUsername() {
        String botUsername = notificationClientProperties.getTelegram().getBotUsername();
        if (botUsername == null || botUsername.isBlank()) {
            throw new ApiException(ErrorCode.NOTIFICATION_ENDPOINT_CONFIGURATION_MISSING);
        }

        String normalized = botUsername.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new ApiException(ErrorCode.NOTIFICATION_ENDPOINT_CONFIGURATION_MISSING);
        }

        return normalized;
    }

    private Optional<String> readTelegramStartToken(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String[] parts = text.trim().split("\\s+", 2);
        if (!"/start".equals(parts[0]) || parts.length < 2 || parts[1].isBlank()) {
            return Optional.empty();
        }

        return Optional.of(parts[1].trim());
    }

    private NotificationEndpointConnectionResult telegramWebhookIgnored() {
        return new NotificationEndpointConnectionResult(
                NotificationChannel.TELEGRAM_DM,
                false,
                null,
                null,
                null,
                "처리할 Telegram 연결 요청이 없습니다."
        );
    }

    private String targetLabel(NotificationChannel channel, String targetAddress) {
        if (channel == NotificationChannel.EMAIL) {
            return maskedEmail(targetAddress);
        }

        return "연결됨";
    }

    private String maskedEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return "연결됨";
        }

        int atIndex = email.indexOf('@');
        String firstCharacter = email.substring(0, 1);
        return firstCharacter + "***" + email.substring(atIndex);
    }

    private String channelLabel(NotificationChannel channel) {
        return switch (channel) {
            case DISCORD_DM -> "Discord";
            case TELEGRAM_DM -> "Telegram";
            case EMAIL -> "Email";
        };
    }
}
