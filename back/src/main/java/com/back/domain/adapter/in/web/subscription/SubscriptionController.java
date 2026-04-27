package com.back.domain.adapter.in.web.subscription;

import com.back.domain.adapter.out.oauth.OAuthClientProperties;
import com.back.domain.adapter.out.persistence.notification.NotificationPreferenceJpaRepository;
import com.back.domain.adapter.out.persistence.schedule.ScheduleJpaRepository;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaEntity;
import com.back.domain.adapter.out.persistence.subscription.SubscriptionJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import com.back.domain.application.service.CurrentUserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.back.domain.model.notification.NotificationChannel;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import org.springframework.transaction.annotation.Transactional;

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
    private final SubscriptionJpaRepository subscriptionRepository;
    private final ScheduleJpaRepository scheduleRepository;
    private final NotificationPreferenceJpaRepository notificationPreferenceRepository;

    @GetMapping
    public List<SubscriptionSummaryResponse> list(HttpServletRequest servletRequest) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(servletRequest));
        return subscriptionRepository.findByUserIdAndActiveTrue(user.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

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

    @DeleteMapping("/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(
            @PathVariable String subscriptionId,
            HttpServletRequest servletRequest
    ) {
        UserJpaEntity user = currentUserService.requireCurrentUser(readSessionCookie(servletRequest));
        SubscriptionJpaEntity subscription = subscriptionRepository
                .findByIdAndUserIdAndActiveTrue(subscriptionId, user.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        subscription.deactivate();
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

    private SubscriptionSummaryResponse toSummary(SubscriptionJpaEntity subscription) {
        String cronExpr = scheduleRepository.findFirstBySubscriptionIdOrderByNextRunAsc(subscription.getId())
                .map(schedule -> schedule.getCronExpr())
                .orElse(null);
        java.time.LocalDateTime nextRun = scheduleRepository.findFirstBySubscriptionIdOrderByNextRunAsc(subscription.getId())
                .map(schedule -> schedule.getNextRun())
                .orElse(null);
        NotificationChannel channel = notificationPreferenceRepository
                .findBySubscriptionIdAndEnabledTrue(subscription.getId())
                .stream()
                .findFirst()
                .map(preference -> preference.getChannel())
                .orElse(null);

        return new SubscriptionSummaryResponse(
                subscription.getId(),
                subscription.getQuery(),
                domainLabel(subscription.getDomain().getName()),
                cadenceLabel(cronExpr),
                channel,
                channelLabel(channel),
                nextRun,
                subscription.isActive()
        );
    }

    private String domainLabel(String domainName) {
        return switch (domainName == null ? "" : domainName) {
            case "real-estate" -> "부동산";
            case "law-regulation" -> "법률/규제";
            case "recruitment" -> "채용";
            case "auction" -> "경매/희소매물";
            default -> domainName;
        };
    }

    private String cadenceLabel(String cronExpr) {
        return switch (cronExpr == null ? "" : cronExpr) {
            case "0 0 * * * *" -> "매시간";
            case "0 0 9 * * *" -> "매일 오전 9시";
            case "0 0 9 * * MON-FRI" -> "평일 오전 9시";
            default -> cronExpr;
        };
    }

    private String channelLabel(NotificationChannel channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case TELEGRAM_DM -> "Telegram";
            case DISCORD_DM -> "Discord";
            case EMAIL -> "Email";
        };
    }
}
