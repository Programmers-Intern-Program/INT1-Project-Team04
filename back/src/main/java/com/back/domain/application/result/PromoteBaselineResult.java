package com.back.domain.application.result;

public record PromoteBaselineResult(
        Status status,
        String subscriptionId,
        int rowsUpdated,
        String reason
) {

    public enum Status {
        SUCCESS,
        DRY_RUN,
        ALREADY_USED,
        EXPIRED,
        NOT_FOUND,
        MCP_FAILED
    }

    public static PromoteBaselineResult success(String subscriptionId, int rowsUpdated) {
        return new PromoteBaselineResult(Status.SUCCESS, subscriptionId, rowsUpdated, null);
    }

    public static PromoteBaselineResult dryRun(String subscriptionId) {
        return new PromoteBaselineResult(Status.DRY_RUN, subscriptionId, 0, null);
    }

    public static PromoteBaselineResult alreadyUsed() {
        return new PromoteBaselineResult(Status.ALREADY_USED, null, 0, null);
    }

    public static PromoteBaselineResult expired() {
        return new PromoteBaselineResult(Status.EXPIRED, null, 0, null);
    }

    public static PromoteBaselineResult notFound() {
        return new PromoteBaselineResult(Status.NOT_FOUND, null, 0, null);
    }

    public static PromoteBaselineResult mcpFailed(String subscriptionId, String reason) {
        return new PromoteBaselineResult(Status.MCP_FAILED, subscriptionId, 0, reason);
    }
}
