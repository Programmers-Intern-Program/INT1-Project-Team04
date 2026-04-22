package com.back.domain.application.command;

public record CreateSubscriptionCommand(
        Long userId,
        Long domainId,
        String query,
        String cronExpr
) {
}
