package com.back.domain.adapter.in.web.subscriptionconversation;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.service.CurrentUserService;
import com.back.domain.application.service.subscriptionconversation.SubscriptionConversationService;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscription-conversations")
@RequiredArgsConstructor
public class SubscriptionConversationController {

    private final SubscriptionConversationService conversationService;
    private final CurrentUserService currentUserService;
    private final OAuthClientProperties properties;

    @PostMapping("/messages")
    public SubscriptionConversationService.Response message(
            @RequestBody SubscriptionConversationMessageRequest request,
            HttpServletRequest servletRequest
    ) {
        if (request == null || (isBlank(request.message()) && request.action() == null)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(servletRequest));
        return conversationService.handle(
                user.getId(),
                request.conversationId(),
                request.message(),
                request.toServiceAction()
        );
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
