package com.back.domain.adapter.in.web.auth;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.oauth.OAuthProviderClient;
import com.back.domain.adapter.out.oauth.OAuthProviderClientRegistry;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.result.MemberResult;
import com.back.domain.application.result.OAuthLoginResult;
import com.back.domain.application.service.CurrentUserService;
import com.back.domain.application.service.MemberService;
import com.back.domain.application.service.OAuthLoginService;
import com.back.domain.model.user.OAuthProvider;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final OAuthProviderClientRegistry providerClientRegistry;
    private final OAuthLoginService oauthLoginService;
    private final CurrentUserService currentUserService;
    private final MemberService memberService;
    private final OAuthClientProperties properties;

    @GetMapping("/oauth/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        OAuthProvider oauthProvider = OAuthProvider.fromPath(provider);
        OAuthProviderClient client = providerClientRegistry.get(oauthProvider);
        String state = UUID.randomUUID().toString();

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(client.authorizationUri(state))
                .header(HttpHeaders.SET_COOKIE, stateCookie(state).toString())
                .build();
    }

    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state,
            HttpServletRequest request
    ) {
        String expectedState = readCookie(request, properties.getAuth().getStateCookieName())
                .orElseThrow(() -> new ApiException(ErrorCode.OAUTH_STATE_MISMATCH));
        if (!expectedState.equals(state)) {
            throw new ApiException(ErrorCode.OAUTH_STATE_MISMATCH);
        }

        OAuthProvider oauthProvider = OAuthProvider.fromPath(provider);
        OAuthLoginResult login = oauthLoginService.login(providerClientRegistry.get(oauthProvider).fetchProfile(code));

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(properties.getFrontend().getBaseUrl()))
                .header(HttpHeaders.SET_COOKIE, sessionCookie(login.rawSessionToken()).toString())
                .header(HttpHeaders.SET_COOKIE, clearCookie(properties.getAuth().getStateCookieName()).toString())
                .build();
    }

    @GetMapping("/me")
    public MemberResult me(HttpServletRequest request) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request).orElse(null));
        return memberService.get(user.getId());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        readSessionCookie(request).ifPresent(memberService::logout);
        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie(properties.getAuth().getSessionCookieName()).toString())
                .build();
    }

    private Optional<String> readSessionCookie(HttpServletRequest request) {
        return readCookie(request, properties.getAuth().getSessionCookieName());
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        return Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals(name))
                .map(Cookie::getValue)
                .findFirst();
    }

    private ResponseCookie stateCookie(String value) {
        return baseCookie(properties.getAuth().getStateCookieName(), value)
                .maxAge(Duration.ofMinutes(5))
                .build();
    }

    private ResponseCookie sessionCookie(String value) {
        return baseCookie(properties.getAuth().getSessionCookieName(), value)
                .maxAge(Duration.ofDays(properties.getAuth().getSessionDays()))
                .build();
    }

    private ResponseCookie clearCookie(String name) {
        return baseCookie(name, "")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.getAuth().isSecureCookies())
                .sameSite("Lax")
                .path("/");
    }
}
