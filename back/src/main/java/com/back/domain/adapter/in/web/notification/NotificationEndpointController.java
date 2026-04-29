package com.back.domain.adapter.in.web.notification;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.oauth.OAuthProviderClient;
import com.back.domain.adapter.out.oauth.OAuthProviderClientRegistry;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.result.NotificationEndpointConnectionResult;
import com.back.domain.application.service.CurrentUserService;
import com.back.domain.application.service.NotificationEndpointConnectionService;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.user.OAuthProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
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

    private static final String DISCORD_NOTIFICATION_CONNECT_COOKIE = "DISCORD_NOTIFICATION_CONNECT";

    private final NotificationEndpointConnectionService connectionService;
    private final CurrentUserService currentUserService;
    private final OAuthProviderClientRegistry providerClientRegistry;
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

    @GetMapping("/discord/authorize")
    public ResponseEntity<Void> authorizeDiscordNotification(HttpServletRequest request) {
        currentUserService.requireCurrentUser(readSessionCookie(request));
        OAuthProviderClient client = providerClientRegistry.get(OAuthProvider.DISCORD);
        String state = UUID.randomUUID().toString();

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(client.authorizationUri(state))
                .header(HttpHeaders.SET_COOKIE, stateCookie(state).toString())
                .header(HttpHeaders.SET_COOKIE, discordNotificationConnectCookie().toString())
                .build();
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
                .path("/api/notification-endpoints/discord/authorize")
                .toUriString();
    }

    private ResponseCookie stateCookie(String value) {
        return baseCookie(properties.getAuth().getStateCookieName(), value)
                .maxAge(Duration.ofMinutes(5))
                .build();
    }

    private ResponseCookie discordNotificationConnectCookie() {
        return baseCookie(DISCORD_NOTIFICATION_CONNECT_COOKIE, "true")
                .maxAge(Duration.ofMinutes(5))
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.getAuth().isSecureCookies())
                .sameSite("Lax")
                .path("/");
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
