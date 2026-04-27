package com.back.domain.adapter.in.web.subscription;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import com.back.domain.application.service.CurrentUserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Incoming Web Adapter] 구독 생성 요청을 처리하는 REST controller
 */
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final CreateSubscriptionUseCase createSubscriptionUseCase;
    private final CurrentUserService currentUserService;
    private final OAuthClientProperties properties;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse create(
            @Valid @RequestBody CreateSubscriptionRequest request,
            HttpServletRequest servletRequest
    ) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(servletRequest));
        return SubscriptionResponse.from(createSubscriptionUseCase.createForUser(
                user.getId(),
                new CreateSubscriptionCommand(
                        request.domainId(),
                        request.query(),
                        request.cronExpr(),
                        request.notificationChannel(),
                        request.notificationTargetAddress()
                )
        ));
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
}
