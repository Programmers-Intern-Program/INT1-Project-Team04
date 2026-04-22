package com.back.domain.adapter.in.web.member;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.result.MemberResult;
import com.back.domain.application.service.CurrentUserService;
import com.back.domain.application.service.MemberService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final CurrentUserService currentUserService;
    private final MemberService memberService;
    private final OAuthClientProperties properties;

    @GetMapping("/me")
    public MemberResult me(HttpServletRequest request) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        return memberService.get(user.getId());
    }

    @PatchMapping("/me")
    public MemberResult update(@Valid @RequestBody UpdateMemberRequest request, HttpServletRequest servletRequest) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(servletRequest));
        return memberService.updateNickname(user.getId(), request.nickname());
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(HttpServletRequest request) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(request));
        memberService.withdraw(user.getId());
        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                .build();
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

    private ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(properties.getAuth().getSessionCookieName(), "")
                .httpOnly(true)
                .secure(properties.getAuth().isSecureCookies())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
