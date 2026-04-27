package com.back.domain.adapter.in.web.notification;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.result.NotificationEndpointConnectionResult;
import com.back.domain.application.service.CurrentUserService;
import com.back.domain.application.service.NotificationEndpointConnectionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import com.back.domain.model.notification.NotificationChannel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/notification-endpoints")
@RequiredArgsConstructor
public class NotificationEndpointController {

    private final NotificationEndpointConnectionService connectionService;
    private final CurrentUserService currentUserService;
    private final OAuthClientProperties properties;

    @GetMapping
    public List<NotificationEndpointStatusResponse> statuses(HttpServletRequest request) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        return connectionService.loadStatuses(user.getId())
                .stream()
                .map(NotificationEndpointStatusResponse::from)
                .toList();
    }

    @PostMapping("/discord/connect")
    public NotificationEndpointConnectionResponse connectDiscord(HttpServletRequest request) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        return NotificationEndpointConnectionResponse.from(connectionService.connectDiscord(
                user.getId(),
                discordAuthorizationUrl()
        ));
    }

    @PostMapping("/telegram/connect")
    public NotificationEndpointConnectionResponse connectTelegram(HttpServletRequest request) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        return NotificationEndpointConnectionResponse.from(connectionService.startTelegramConnection(user.getId()));
    }

    @DeleteMapping("/{channel}")
    public NotificationEndpointConnectionResponse disconnect(
            @PathVariable NotificationChannel channel,
            HttpServletRequest request
    ) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        return NotificationEndpointConnectionResponse.from(connectionService.disconnect(user.getId(), channel));
    }

    @PostMapping("/telegram/webhook")
    public TelegramWebhookResponse telegramWebhook(@RequestBody(required = false) TelegramWebhookRequest request) {
        NotificationEndpointConnectionResult result = connectionService.completeTelegramConnection(
                telegramMessageText(request),
                telegramChatId(request)
        );
        return new TelegramWebhookResponse(result.connected(), result.message());
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals(properties.getAuth().getSessionCookieName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String discordAuthorizationUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/auth/oauth/discord/authorize")
                .toUriString();
    }

    private String telegramMessageText(TelegramWebhookRequest request) {
        if (request == null || request.message() == null) {
            return null;
        }

        return request.message().text();
    }

    private Long telegramChatId(TelegramWebhookRequest request) {
        if (request == null || request.message() == null || request.message().chat() == null) {
            return null;
        }

        return request.message().chat().id();
    }
}
