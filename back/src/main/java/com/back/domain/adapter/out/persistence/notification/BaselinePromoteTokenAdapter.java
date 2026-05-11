package com.back.domain.adapter.out.persistence.notification;

import com.back.domain.application.port.out.ConsumeBaselinePromoteTokenPort;
import com.back.domain.application.port.out.IssueBaselinePromoteTokenPort;
import com.back.domain.application.result.PromoteTokenConsumption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class BaselinePromoteTokenAdapter
        implements IssueBaselinePromoteTokenPort, ConsumeBaselinePromoteTokenPort {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final BaselinePromoteTokenJpaRepository repository;

    @Override
    @Transactional
    public String issue(
            String subscriptionId,
            String paramsHash,
            Long userId,
            String notificationId,
            Duration ttl
    ) {
        String token = generateToken();
        LocalDateTime now = LocalDateTime.now();
        repository.save(new BaselinePromoteTokenJpaEntity(
                token,
                subscriptionId,
                paramsHash,
                userId,
                notificationId,
                now,
                now.plus(ttl)
        ));
        return token;
    }

    @Override
    @Transactional
    public PromoteTokenConsumption consumeOrPeek(String token, boolean dryRun) {
        if (token == null || token.isBlank()) {
            return PromoteTokenConsumption.notFound();
        }

        Optional<BaselinePromoteTokenJpaEntity> entity = repository.findById(token);
        if (entity.isEmpty()) {
            return PromoteTokenConsumption.notFound();
        }

        BaselinePromoteTokenJpaEntity stored = entity.get();
        LocalDateTime now = LocalDateTime.now();

        if (stored.isUsed()) {
            return PromoteTokenConsumption.alreadyUsed();
        }
        if (stored.isExpired(now)) {
            return PromoteTokenConsumption.expired();
        }

        if (dryRun) {
            return PromoteTokenConsumption.dryRun(
                    stored.getSubscriptionId(),
                    stored.getParamsHash(),
                    stored.getUserId(),
                    stored.getNotificationId()
            );
        }

        stored.markUsed(now);
        return PromoteTokenConsumption.usable(
                stored.getSubscriptionId(),
                stored.getParamsHash(),
                stored.getUserId(),
                stored.getNotificationId()
        );
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
