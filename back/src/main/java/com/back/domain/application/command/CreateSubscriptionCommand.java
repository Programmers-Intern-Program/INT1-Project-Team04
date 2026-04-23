package com.back.domain.application.command;

public record CreateSubscriptionCommand(
        Long domainId,
        String query,
        String cronExpr
) {
}
