package com.back.domain.adapter.in.web.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSubscriptionRequest(
        @NotNull Long userId,
        @NotNull Long domainId,
        @NotBlank String query,
        @NotBlank String cronExpr
) {
}
