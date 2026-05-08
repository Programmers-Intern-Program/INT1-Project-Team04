package com.back.domain.application.result;

public record PromoteTokenConsumption(
        Status status,
        String subscriptionId,
        String paramsHash,
        Long userId,
        String notificationId
) {

    public enum Status {
        USABLE,
        DRY_RUN,
        NOT_FOUND,
        EXPIRED,
        ALREADY_USED
    }

    public static PromoteTokenConsumption notFound() {
        return new PromoteTokenConsumption(Status.NOT_FOUND, null, null, null, null);
    }

    public static PromoteTokenConsumption expired() {
        return new PromoteTokenConsumption(Status.EXPIRED, null, null, null, null);
    }

    public static PromoteTokenConsumption alreadyUsed() {
        return new PromoteTokenConsumption(Status.ALREADY_USED, null, null, null, null);
    }

    public static PromoteTokenConsumption usable(
            String subscriptionId,
            String paramsHash,
            Long userId,
            String notificationId
    ) {
        return new PromoteTokenConsumption(Status.USABLE, subscriptionId, paramsHash, userId, notificationId);
    }

    public static PromoteTokenConsumption dryRun(
            String subscriptionId,
            String paramsHash,
            Long userId,
            String notificationId
    ) {
        return new PromoteTokenConsumption(Status.DRY_RUN, subscriptionId, paramsHash, userId, notificationId);
    }
}
