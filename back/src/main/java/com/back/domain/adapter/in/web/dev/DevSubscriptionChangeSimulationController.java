package com.back.domain.adapter.in.web.dev;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.service.CurrentUserService;
import com.back.domain.application.service.dev.DevSubscriptionChangeSimulationResult;
import com.back.domain.application.service.dev.DevSubscriptionChangeSimulationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/dev/subscriptions")
@RequiredArgsConstructor
public class DevSubscriptionChangeSimulationController {

    private final DevSubscriptionChangeSimulationService simulationService;
    private final CurrentUserService currentUserService;
    private final OAuthClientProperties properties;

    @PostMapping("/{subscriptionId}/simulate-change-alert")
    public DevSubscriptionChangeSimulationResult simulateChangeAlert(
            @PathVariable String subscriptionId,
            HttpServletRequest request
    ) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        return simulationService.simulate(subscriptionId, user.getId(), LocalDateTime.now());
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
